package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.enums.ConfigSourceType;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.utils.ClassLoaderUtil;
import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.ExceptionUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
public class LocalFileConfigRepository extends AbstractConfigRepository
    implements RepositoryChangeListener {
  private static final Logger logger = LoggerFactory.getLogger(LocalFileConfigRepository.class);
  private static final String CONFIG_DIR = "/config-cache";// 配置文件目录
  private final String m_namespace;//Namespace 名字
  private File m_baseDir;// 本地缓存配置文件目录
  private final ConfigUtil m_configUtil;
  private volatile Properties m_fileProperties;//配置文件 Properties
  //上游的 ConfigRepository 对象。一般情况下，使用 RemoteConfigRepository 对象，读取远程 Config Service 的配置
  private volatile ConfigRepository m_upstream;

  private volatile ConfigSourceType m_sourceType = ConfigSourceType.LOCAL;

  /**
   * Constructor.
   *
   * @param namespace the namespace
   */
  public LocalFileConfigRepository(String namespace) {
    this(namespace, null);
  }

  /**
   * findLocalCacheDir 获得本地缓存文件的目录
   * @param namespace
   * @param upstream
   */
  public LocalFileConfigRepository(String namespace, ConfigRepository upstream) {
    m_namespace = namespace;
    m_configUtil = ApolloInjector.getInstance(ConfigUtil.class);
    // 获得本地缓存配置文件的目录
    this.setLocalCacheDir(findLocalCacheDir(), false);
    this.setUpstreamRepository(upstream);
    this.trySync();//初始化同步
  }

  /**
   若 syncImmediately = true ，则进行同步。目前仅在单元测试中，会出现这种情况。正式的代码，syncImmediately = false
   * @param baseDir
   * @param syncImmediately
   */
  void setLocalCacheDir(File baseDir, boolean syncImmediately) {
    m_baseDir = baseDir;
    // 获得本地缓存配置文件的目录，校验本地缓存配置目录是否存在。若不存在，则进行创建
    this.checkLocalConfigCacheDir(m_baseDir);
    if (syncImmediately) {
      this.trySync();
    }
  }

  /**
   * 获得本地缓存配置文件的目录
   * 在非 Windows 的环境下，是 /opt/data/${appId} 目录。
   调用 Files#exists(path) 方法，判断若默认缓存配置目录不存在，进行创建。😈但是，可能我们的应用程序没有该目录的权限，
   此时会导致创建失败。那么就有会出现两种情况：
   第一种，有权限，使用 /opt/data/${appId}/ + config-cache 目录。
   第二种，无权限，使用 ClassPath/ + config-cache 目录。这个目录，应用程序下，肯定是有权限的。
   * @return
   */
  private File findLocalCacheDir() {
    try {
      String defaultCacheDir = m_configUtil.getDefaultLocalCacheDir();//获得默认的本地缓存目录
      Path path = Paths.get(defaultCacheDir);
      if (!Files.exists(path)) {//不存在进行创建
        Files.createDirectories(path);
      }
      if (Files.exists(path) && Files.isWritable(path)) {//存在并且可写
        return new File(defaultCacheDir, CONFIG_DIR);
      }
    } catch (Throwable ex) {
      //ignore
    }

    return new File(ClassLoaderUtil.getClassPath(), CONFIG_DIR);//否则返回当前类路径目录
  }

  @Override
  public Properties getConfig() {
    if (m_fileProperties == null) {
      sync();
    }
    Properties result = new Properties();
    result.putAll(m_fileProperties);
    return result;
  }

  @Override
  public void setUpstreamRepository(ConfigRepository upstreamConfigRepository) {
    if (upstreamConfigRepository == null) {
      return;
    }
    //clear previous listener 从老的 `m_upstream` 移除自己
    if (m_upstream != null) {
      m_upstream.removeChangeListener(this);
    }
    // 设置新的 `m_upstream`
    m_upstream = upstreamConfigRepository;
    // 从 `m_upstream` 拉取配置，比如RemoteConfigRepository
    trySyncFromUpstream();
    // 向新的 `m_upstream` 注册自己
    upstreamConfigRepository.addChangeListener(this);
  }

  @Override
  public ConfigSourceType getSourceType() {
    return m_sourceType;
  }

  /**
   * 当 RemoteRepositoryConfig 读取到配置变更时，调用 #onRepositoryChange(name, newProperties) 方法，
   * 更新 m_fileProperties ，并通知监听器们
   *
   * @param namespace the namespace of this repository change
   * @param newProperties the properties after change
   */

  @Override
  public void onRepositoryChange(String namespace, Properties newProperties) {
    if (newProperties.equals(m_fileProperties)) {
      return;
    }
    Properties newFileProperties = new Properties();
    newFileProperties.putAll(newProperties);
    updateFileProperties(newFileProperties, m_upstream.getSourceType());
    // 发布 Repository 的配置发生变化，触发对应的监听器们
    this.fireRepositoryChange(namespace, newProperties);
  }

  /**
   * 本地模式的数据同步
   * 在非本地模式的情况下，LocalFileConfigRepository 在初始化时，会首先从远程 Config Service 同步( 加载 )配置。
   * 若同步(加载)失败，则读取本地缓存的配置文件。

   在本地模式的情况下，则只读取本地缓存的配置文件。当然，严格来说，也不一定是缓存，可以是开发者，手动创建的配置文件
   *
   */

  @Override
  protected void sync() {
    //sync with upstream immediately  从上游拉取配置文件
    boolean syncFromUpstreamResultSuccess = trySyncFromUpstream();

    if (syncFromUpstreamResultSuccess) {  //从上游拉取成功即可返回
      return;
    }
    //否则从本地的配置文件中读取
    Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "syncLocalConfig");
    Throwable exception = null;
    try {
      transaction.addData("Basedir", m_baseDir.getAbsolutePath());
      m_fileProperties = this.loadFromLocalCacheFile(m_baseDir, m_namespace);
      m_sourceType = ConfigSourceType.LOCAL;
      transaction.setStatus(Transaction.SUCCESS);
    } catch (Throwable ex) {
      Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
      transaction.setStatus(ex);
      exception = ex;
      //ignore
    } finally {
      transaction.complete();
    }

    if (m_fileProperties == null) {
      m_sourceType = ConfigSourceType.NONE;
      throw new ApolloConfigException(
          "Load config from local config failed!", exception);
    }
  }

  /**
   * 从上游拉取配置文件
   * @return
   */
  private boolean trySyncFromUpstream() {
    if (m_upstream == null) {
      return false;
    }
    try {//从远端获取配置，然后更新本地
      updateFileProperties(m_upstream.getConfig(), m_upstream.getSourceType());
      return true;
    } catch (Throwable ex) {
      Tracer.logError(ex);
      logger
          .warn("Sync config from upstream repository {} failed, reason: {}", m_upstream.getClass(),
              ExceptionUtil.getDetailMessage(ex));
    }
    return false;
  }

  //更新配置文件
  private synchronized void updateFileProperties(Properties newProperties, ConfigSourceType sourceType) {
    this.m_sourceType = sourceType;
    if (newProperties.equals(m_fileProperties)) {
      return;
    }
    this.m_fileProperties = newProperties;
    persistLocalCacheFile(m_baseDir, m_namespace);//持久化缓存文件到本地
  }

  /**
   从缓存配置文件，读取 Properties
   * @param baseDir
   * @param namespace
   * @return
   * @throws IOException
   */
  private Properties loadFromLocalCacheFile(File baseDir, String namespace) throws IOException {
    Preconditions.checkNotNull(baseDir, "Basedir cannot be null");

    File file = assembleLocalCacheFile(baseDir, namespace);//获取缓存文件的路径
    Properties properties = null;

    if (file.isFile() && file.canRead()) {//判断文件是否有效
      InputStream in = null;

      try {//读取文件
        in = new FileInputStream(file);

        properties = new Properties();
        properties.load(in);
        logger.debug("Loading local config file {} successfully!", file.getAbsolutePath());
      } catch (IOException ex) {
        Tracer.logError(ex);
        throw new ApolloConfigException(String
            .format("Loading config from local cache file %s failed", file.getAbsolutePath()), ex);
      } finally {
        try {
          if (in != null) {
            in.close();
          }
        } catch (IOException ex) {
          // ignore
        }
      }
    } else {
      throw new ApolloConfigException(
          String.format("Cannot read from local cache file %s", file.getAbsolutePath()));
    }

    return properties;
  }
  //生成缓存文件
  void persistLocalCacheFile(File baseDir, String namespace) {
    if (baseDir == null) {
      return;
    }
    File file = assembleLocalCacheFile(baseDir, namespace);//缓存文件的路径

    OutputStream out = null;

    Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "persistLocalConfigFile");
    transaction.addData("LocalConfigFile", file.getAbsolutePath());
    try {
      out = new FileOutputStream(file);//写入本地放入缓存
      m_fileProperties.store(out, "Persisted by DefaultConfig");
      transaction.setStatus(Transaction.SUCCESS);
    } catch (IOException ex) {
      ApolloConfigException exception =
          new ApolloConfigException(
              String.format("Persist local cache file %s failed", file.getAbsolutePath()), ex);
      Tracer.logError(exception);
      transaction.setStatus(exception);
      logger.warn("Persist local cache file {} failed, reason: {}.", file.getAbsolutePath(),
          ExceptionUtil.getDetailMessage(ex));
    } finally {
      if (out != null) {
        try {
          out.close();
        } catch (IOException ex) {
          //ignore
        }
      }
      transaction.complete();
    }
  }
  //该方法校验和创建的 config-cache 目录。这个目录在 #findLocalCacheDir() 方法中，并未创建
  private void checkLocalConfigCacheDir(File baseDir) {
    if (baseDir.exists()) {
      return;
    }
    Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "createLocalConfigDir");
    transaction.addData("BaseDir", baseDir.getAbsolutePath());
    try {
      Files.createDirectory(baseDir.toPath());
      transaction.setStatus(Transaction.SUCCESS);
    } catch (IOException ex) {
      ApolloConfigException exception =
          new ApolloConfigException(
              String.format("Create local config directory %s failed", baseDir.getAbsolutePath()),
              ex);
      Tracer.logError(exception);
      transaction.setStatus(exception);
      logger.warn(
          "Unable to create local config cache directory {}, reason: {}. Will not able to cache config file.",
          baseDir.getAbsolutePath(), ExceptionUtil.getDetailMessage(ex));
    } finally {
      transaction.complete();
    }
  }

  /**
   那么完整的缓存配置文件到底路径是什么呢？${baseDir}/config-cache/ + ${appId}+${cluster} + ${namespace}.properties ，
   即 #assembleLocalCacheFile(baseDir, namespace) 方法，拼接完整的本地缓存配置文件的地址
   * @param baseDir
   * @param namespace
   * @return
   */
  File assembleLocalCacheFile(File baseDir, String namespace) {
    String fileName =
        String.format("%s.properties", Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR)
            .join(m_configUtil.getAppId(), m_configUtil.getCluster(), namespace));
    return new File(baseDir, fileName);
  }
}
