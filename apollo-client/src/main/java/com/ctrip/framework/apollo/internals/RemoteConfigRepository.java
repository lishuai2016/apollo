package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.enums.ConfigSourceType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.framework.apollo.Apollo;
import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.dto.ApolloConfig;
import com.ctrip.framework.apollo.core.dto.ApolloNotificationMessages;
import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.core.schedule.ExponentialSchedulePolicy;
import com.ctrip.framework.apollo.core.schedule.SchedulePolicy;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.exceptions.ApolloConfigStatusCodeException;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.ExceptionUtil;
import com.ctrip.framework.apollo.util.http.HttpRequest;
import com.ctrip.framework.apollo.util.http.HttpResponse;
import com.ctrip.framework.apollo.util.http.HttpUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import com.google.common.util.concurrent.RateLimiter;
import com.google.gson.Gson;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
public class RemoteConfigRepository extends AbstractConfigRepository {
  private static final Logger logger = LoggerFactory.getLogger(RemoteConfigRepository.class);
  private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR);
  private static final Joiner.MapJoiner MAP_JOINER = Joiner.on("&").withKeyValueSeparator("=");
  private static final Escaper pathEscaper = UrlEscapers.urlPathSegmentEscaper();
  private static final Escaper queryParamEscaper = UrlEscapers.urlFormParameterEscaper();

  private final ConfigServiceLocator m_serviceLocator;
  private final HttpUtil m_httpUtil;
  private final ConfigUtil m_configUtil;
  private final RemoteConfigLongPollService remoteConfigLongPollService;//http长轮询服务
  private volatile AtomicReference<ApolloConfig> m_configCache;//缓存从config service拉去的配置信息
  private final String m_namespace;
  private final static ScheduledExecutorService m_executorService;
  private final AtomicReference<ServiceDTO> m_longPollServiceDto;
  private final AtomicReference<ApolloNotificationMessages> m_remoteMessages;
  private final RateLimiter m_loadConfigRateLimiter;
  private final AtomicBoolean m_configNeedForceRefresh;
  private final SchedulePolicy m_loadConfigFailSchedulePolicy;
  private final Gson gson;

  static {
    m_executorService = Executors.newScheduledThreadPool(1,
        ApolloThreadFactory.create("RemoteConfigRepository", true));
  }

  /**
   * Constructor.
   *
   * @param namespace the namespace
   */
  public RemoteConfigRepository(String namespace) {
    m_namespace = namespace;
    m_configCache = new AtomicReference<>();
    m_configUtil = ApolloInjector.getInstance(ConfigUtil.class);
    m_httpUtil = ApolloInjector.getInstance(HttpUtil.class);
    m_serviceLocator = ApolloInjector.getInstance(ConfigServiceLocator.class);
    remoteConfigLongPollService = ApolloInjector.getInstance(RemoteConfigLongPollService.class);
    m_longPollServiceDto = new AtomicReference<>();
    m_remoteMessages = new AtomicReference<>();
    m_loadConfigRateLimiter = RateLimiter.create(m_configUtil.getLoadConfigQPS());
    m_configNeedForceRefresh = new AtomicBoolean(true);
    m_loadConfigFailSchedulePolicy = new ExponentialSchedulePolicy(m_configUtil.getOnErrorRetryInterval(),
        m_configUtil.getOnErrorRetryInterval() * 8);
    gson = new Gson();
    this.trySync();
    this.schedulePeriodicRefresh();
    this.scheduleLongPollingRefresh();
  }

  @Override
  public Properties getConfig() {
    if (m_configCache.get() == null) { //缓存中没有
      this.sync();
    }
    return transformApolloConfigToProperties(m_configCache.get());
  }

  @Override
  public void setUpstreamRepository(ConfigRepository upstreamConfigRepository) {
    //remote config doesn't need upstream
  }

  @Override
  public ConfigSourceType getSourceType() {
    return ConfigSourceType.REMOTE;
  }

  /**
   * 定时刷新
   */
  private void schedulePeriodicRefresh() {
    logger.debug("Schedule periodic refresh with interval: {} {}",
        m_configUtil.getRefreshInterval(), m_configUtil.getRefreshIntervalTimeUnit());
    m_executorService.scheduleAtFixedRate(
        new Runnable() {
          @Override
          public void run() {
            Tracer.logEvent("Apollo.ConfigService", String.format("periodicRefresh: %s", m_namespace));
            logger.debug("refresh config for namespace: {}", m_namespace);
            trySync();
            Tracer.logEvent("Apollo.Client.Version", Apollo.VERSION);
          }
        }, m_configUtil.getRefreshInterval(), m_configUtil.getRefreshInterval(),
        m_configUtil.getRefreshIntervalTimeUnit());
  }

  /**
   * 拉取远端的配置同步到本地
   */
  @Override
  protected synchronized void sync() {
    Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "syncRemoteConfig");

    try {
      ApolloConfig previous = m_configCache.get();
      ApolloConfig current = loadApolloConfig();//发送http请求到config server拉取最新的配置

      //reference equals means HTTP 304   不相等说明有更新，需要更新缓存
      if (previous != current) {
        logger.debug("Remote Config refreshed!");
        m_configCache.set(current);
        this.fireRepositoryChange(m_namespace, this.getConfig());//触发监听器
      }

      if (current != null) {
        Tracer.logEvent(String.format("Apollo.Client.Configs.%s", current.getNamespaceName()),
            current.getReleaseKey());
      }

      transaction.setStatus(Transaction.SUCCESS);
    } catch (Throwable ex) {
      transaction.setStatus(ex);
      throw ex;
    } finally {
      transaction.complete();
    }
  }

  //把apolloConfig中的配置封装成Properties
  private Properties transformApolloConfigToProperties(ApolloConfig apolloConfig) {
    Properties result = new Properties();
    result.putAll(apolloConfig.getConfigurations());
    return result;
  }

  /**
   * 拉取远程的配置服务
   * @return
   */
  private ApolloConfig loadApolloConfig() {
    if (!m_loadConfigRateLimiter.tryAcquire(5, TimeUnit.SECONDS)) {
      //wait at most 5 seconds
      try {
        TimeUnit.SECONDS.sleep(5);
      } catch (InterruptedException e) {
      }
    }
    String appId = m_configUtil.getAppId();
    String cluster = m_configUtil.getCluster();
    String dataCenter = m_configUtil.getDataCenter();
    Tracer.logEvent("Apollo.Client.ConfigMeta", STRING_JOINER.join(appId, cluster, m_namespace));
    int maxRetries = m_configNeedForceRefresh.get() ? 2 : 1;
    long onErrorSleepTime = 0; // 0 means no sleep
    Throwable exception = null;

    List<ServiceDTO> configServices = getConfigServices();//从meta server获得配置服务列表
    String url = null;
    for (int i = 0; i < maxRetries; i++) {
      List<ServiceDTO> randomConfigServices = Lists.newLinkedList(configServices);
      Collections.shuffle(randomConfigServices);
      //Access the server which notifies the client first
      if (m_longPollServiceDto.get() != null) {
        randomConfigServices.add(0, m_longPollServiceDto.getAndSet(null));
      }

      for (ServiceDTO configService : randomConfigServices) {
        if (onErrorSleepTime > 0) {
          logger.warn(
              "Load config failed, will retry in {} {}. appId: {}, cluster: {}, namespaces: {}",
              onErrorSleepTime, m_configUtil.getOnErrorRetryIntervalTimeUnit(), appId, cluster, m_namespace);

          try {
            m_configUtil.getOnErrorRetryIntervalTimeUnit().sleep(onErrorSleepTime);
          } catch (InterruptedException e) {
            //ignore
          }
        }
        //拼接查询URL
        url = assembleQueryConfigUrl(configService.getHomepageUrl(), appId, cluster, m_namespace,
                dataCenter, m_remoteMessages.get(), m_configCache.get());

        //Loading config from http://10.181.163.3:80/configs/test_yml/default/application?ip=10.12.128.64
        logger.debug("Loading config from {}", url);
        HttpRequest request = new HttpRequest(url);

        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "queryConfig");
        transaction.addData("Url", url);
        try {
          //发送请求然后封装结果
          HttpResponse<ApolloConfig> response = m_httpUtil.doGet(request, ApolloConfig.class);
          m_configNeedForceRefresh.set(false);
          m_loadConfigFailSchedulePolicy.success();

          transaction.addData("StatusCode", response.getStatusCode());
          transaction.setStatus(Transaction.SUCCESS);

          if (response.getStatusCode() == 304) {
            logger.debug("Config server responds with 304 HTTP status code.");
            return m_configCache.get();//服务端没有发生变化，读取缓存中的内容
          }

          ApolloConfig result = response.getBody();

          logger.debug("Loaded config for {}: {}", m_namespace, result);  //每次发布的时候会触发这里

          return result;
        } catch (ApolloConfigStatusCodeException ex) {
          ApolloConfigStatusCodeException statusCodeException = ex;
          //config not found
          if (ex.getStatusCode() == 404) {
            String message = String.format(
                "Could not find config for namespace - appId: %s, cluster: %s, namespace: %s, " +
                    "please check whether the configs are released in Apollo!",
                appId, cluster, m_namespace);
            statusCodeException = new ApolloConfigStatusCodeException(ex.getStatusCode(),
                message);
          }
          Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(statusCodeException));
          transaction.setStatus(statusCodeException);
          exception = statusCodeException;
        } catch (Throwable ex) {
          Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
          transaction.setStatus(ex);
          exception = ex;
        } finally {
          transaction.complete();
        }

        // if force refresh, do normal sleep, if normal config load, do exponential sleep
        onErrorSleepTime = m_configNeedForceRefresh.get() ? m_configUtil.getOnErrorRetryInterval() :
            m_loadConfigFailSchedulePolicy.fail();
      }

    }
    String message = String.format(
        "Load Apollo Config failed - appId: %s, cluster: %s, namespace: %s, url: %s",
        appId, cluster, m_namespace, url);
    throw new ApolloConfigException(message, exception);
  }

  String assembleQueryConfigUrl(String uri, String appId, String cluster, String namespace,
                                String dataCenter, ApolloNotificationMessages remoteMessages, ApolloConfig previousConfig) {

    //最后返回的格式：http://10.181.163.3:80/configs/test_yml/default/application?ip=10.12.128.64
    String path = "configs/%s/%s/%s";
    List<String> pathParams =
        Lists.newArrayList(pathEscaper.escape(appId), pathEscaper.escape(cluster),
            pathEscaper.escape(namespace));
    Map<String, String> queryParams = Maps.newHashMap();//拼接查询参数

    if (previousConfig != null) {
      queryParams.put("releaseKey", queryParamEscaper.escape(previousConfig.getReleaseKey()));
    }

    if (!Strings.isNullOrEmpty(dataCenter)) {
      queryParams.put("dataCenter", queryParamEscaper.escape(dataCenter));
    }

    String localIp = m_configUtil.getLocalIp();
    if (!Strings.isNullOrEmpty(localIp)) {
      queryParams.put("ip", queryParamEscaper.escape(localIp));
    }

    if (remoteMessages != null) {
      queryParams.put("messages", queryParamEscaper.escape(gson.toJson(remoteMessages)));
    }

    String pathExpanded = String.format(path, pathParams.toArray());

    if (!queryParams.isEmpty()) {
      pathExpanded += "?" + MAP_JOINER.join(queryParams);//拼接查询参数
    }
    if (!uri.endsWith("/")) {
      uri += "/";
    }
    return uri + pathExpanded;
  }

  /**
   * http长轮询定时
   */
  private void scheduleLongPollingRefresh() {
    remoteConfigLongPollService.submit(m_namespace, this);
  }

  /**
   * 长轮询的回调
   * @param longPollNotifiedServiceDto
   * @param remoteMessages
   */
  public void onLongPollNotified(ServiceDTO longPollNotifiedServiceDto, ApolloNotificationMessages remoteMessages) {
    m_longPollServiceDto.set(longPollNotifiedServiceDto);
    m_remoteMessages.set(remoteMessages);
    m_executorService.submit(new Runnable() {
      @Override
      public void run() {
        m_configNeedForceRefresh.set(true);
        trySync();
      }
    });
  }

  private List<ServiceDTO> getConfigServices() {
    List<ServiceDTO> services = m_serviceLocator.getConfigServices();
    if (services.size() == 0) {
      throw new ApolloConfigException("No available config service");
    }

    return services;
  }
}
