package com.ctrip.framework.apollo.internals;

import java.util.Map;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigFile;
import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.enums.ConfigFileFormat;
import com.ctrip.framework.apollo.spi.ConfigFactory;
import com.ctrip.framework.apollo.spi.ConfigFactoryManager;
import com.google.common.collect.Maps;

/**
 * @author Jason Song(song_s@ctrip.com)
 * 提供获得 Config 和 ConfigFile 对象的接口
 * 实现 ConfigManager 接口，默认配置管理器实现类
 */
public class DefaultConfigManager implements ConfigManager {
  private ConfigFactoryManager m_factoryManager;

  private Map<String, Config> m_configs = Maps.newConcurrentMap();//Config 对象的缓存
  private Map<String, ConfigFile> m_configFiles = Maps.newConcurrentMap();//ConfigFile 对象的缓存

  //，默认配置管理器实现类
  public DefaultConfigManager() {
    m_factoryManager = ApolloInjector.getInstance(ConfigFactoryManager.class);
  }

  /**
   * 根据namespace获取Config对象
   * 当需要获得的 Config 或 ConfigFile 对象不在缓存中时，通过 ConfigFactoryManager
   * ，获得对应的 ConfigFactory 对象，从而创建 Config 或 ConfigFile 对象。
   * @param namespace the namespace
   * @return
   */
  @Override
  public Config getConfig(String namespace) {
    Config config = m_configs.get(namespace);

    if (config == null) {
      synchronized (this) {
        config = m_configs.get(namespace);

        if (config == null) {
          ConfigFactory factory = m_factoryManager.getFactory(namespace);

          config = factory.create(namespace);
          m_configs.put(namespace, config);
        }
      }
    }

    return config;
  }

  @Override
  public ConfigFile getConfigFile(String namespace, ConfigFileFormat configFileFormat) {
    String namespaceFileName = String.format("%s.%s", namespace, configFileFormat.getValue());
    ConfigFile configFile = m_configFiles.get(namespaceFileName);

    if (configFile == null) {
      synchronized (this) {
        configFile = m_configFiles.get(namespaceFileName);

        if (configFile == null) {
          ConfigFactory factory = m_factoryManager.getFactory(namespaceFileName);

          configFile = factory.createConfigFile(namespaceFileName, configFileFormat);
          m_configFiles.put(namespaceFileName, configFile);
        }
      }
    }

    return configFile;
  }
}
