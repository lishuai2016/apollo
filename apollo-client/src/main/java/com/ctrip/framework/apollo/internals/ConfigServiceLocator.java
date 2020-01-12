package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.core.ServiceNameConsts;
import com.ctrip.framework.foundation.Foundation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
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
import com.google.gson.reflect.TypeToken;

/**
 * 客户端
 Config Service 定位器。

 1、初始时，从 Meta Service 获取 Config Service 集群地址进行缓存。
 2、定时任务，每 5 分钟，从 Meta Service 获取 Config Service 集群地址刷新缓存。

 问题：
 这个实例什么时候构建的

 */
public class ConfigServiceLocator {
  private static final Logger logger = LoggerFactory.getLogger(ConfigServiceLocator.class);
  private HttpUtil m_httpUtil;
  private ConfigUtil m_configUtil;
  private AtomicReference<List<ServiceDTO>> m_configServices;//configServices地址
  private Type m_responseType;
  private ScheduledExecutorService m_executorService;
  private static final Joiner.MapJoiner MAP_JOINER = Joiner.on("&").withKeyValueSeparator("=");
  private static final Escaper queryParamEscaper = UrlEscapers.urlFormParameterEscaper();

  /**
   * Create a config service locator.
   */
  public ConfigServiceLocator() {
    List<ServiceDTO> initial = Lists.newArrayList();
    m_configServices = new AtomicReference<>(initial);
    m_responseType = new TypeToken<List<ServiceDTO>>() {
    }.getType();
    m_httpUtil = ApolloInjector.getInstance(HttpUtil.class);
    m_configUtil = ApolloInjector.getInstance(ConfigUtil.class);
    this.m_executorService = Executors.newScheduledThreadPool(1,
        ApolloThreadFactory.create("ConfigServiceLocator", true));
    initConfigServices();
  }

  private void initConfigServices() {
    // get from run time configurations  从本地设置的参数中获取configService服务的地址
    List<ServiceDTO> customizedConfigServices = getCustomizedConfigService();

    if (customizedConfigServices != null) {
      setConfigServices(customizedConfigServices);
      return;
    }

    // update from meta service
    // 初始拉取 Config Service 地址
    this.tryUpdateConfigServices();
    // 创建定时任务，定时拉取 Config Service 地址
    this.schedulePeriodicRefresh();
  }

  /**
   * 直接获取本地配置的configservice服务，不经过meta service获取
   * @return
   */
  private List<ServiceDTO> getCustomizedConfigService() {
    // 1. Get from System Property
    String configServices = System.getProperty("apollo.configService");
    if (Strings.isNullOrEmpty(configServices)) {
      // 2. Get from OS environment variable
      configServices = System.getenv("APOLLO_CONFIGSERVICE");
    }
    if (Strings.isNullOrEmpty(configServices)) {
      // 3. Get from server.properties
      configServices = Foundation.server().getProperty("apollo.configService", null);
    }

    if (Strings.isNullOrEmpty(configServices)) {
      return null;
    }

    logger.warn("Located config services from apollo.configService configuration: {}, will not refresh config services from remote meta service!", configServices);

    // mock service dto list
    String[] configServiceUrls = configServices.split(",");
    List<ServiceDTO> serviceDTOS = Lists.newArrayList();

    for (String configServiceUrl : configServiceUrls) {
      configServiceUrl = configServiceUrl.trim();
      ServiceDTO serviceDTO = new ServiceDTO();
      serviceDTO.setHomepageUrl(configServiceUrl);
      serviceDTO.setAppName(ServiceNameConsts.APOLLO_CONFIGSERVICE);
      serviceDTO.setInstanceId(configServiceUrl);
      serviceDTOS.add(serviceDTO);
    }

    return serviceDTOS;
  }

  /**
   * Get the config service info from remote meta server.
   *
   * @return the services dto
   * 缓存为空的时候，从remote meta server拉取更新
   */
  public List<ServiceDTO> getConfigServices() {
    if (m_configServices.get().isEmpty()) {
      updateConfigServices();
    }

    return m_configServices.get();
  }
  //从meta server拉去缓存的入口
  private boolean tryUpdateConfigServices() {
    try {
      updateConfigServices();
      return true;
    } catch (Throwable ex) {
      //ignore
    }
    return false;
  }

  /**
   * 定时从meta service拉取一次config service的地址，然后放到缓存中
   */
  private void schedulePeriodicRefresh() {
    this.m_executorService.scheduleAtFixedRate(
        new Runnable() {
          @Override
          public void run() {
            logger.debug("refresh config services");
            Tracer.logEvent("Apollo.MetaService", "periodicRefresh");
            tryUpdateConfigServices();
          }
        }, m_configUtil.getRefreshInterval(), m_configUtil.getRefreshInterval(),
        m_configUtil.getRefreshIntervalTimeUnit());
  }

  /**
   * 从MetaService拉取配置服务放到缓存中
   */
  private synchronized void updateConfigServices() {
    // 拼接请求 Meta Service URL[重点]
    String url = assembleMetaServiceUrl();

    HttpRequest request = new HttpRequest(url);
    int maxRetries = 2;// 重试两次
    Throwable exception = null;

    // 循环 ，获取 Config Service 地址
    for (int i = 0; i < maxRetries; i++) {
      Transaction transaction = Tracer.newTransaction("Apollo.MetaService", "getConfigService");
      transaction.addData("Url", url);
      try {
        //发送http://meta server/services/config请求到meta server地址上，获得config service列表
        HttpResponse<List<ServiceDTO>> response = m_httpUtil.doGet(request, m_responseType);
        transaction.setStatus(Transaction.SUCCESS);
        // 获得结果 ServiceDTO 数组
        List<ServiceDTO> services = response.getBody();
        if (services == null || services.isEmpty()) {
          logConfigService("Empty response!");
          continue;
        }
        // 更新缓存
        setConfigServices(services);
        return;
      } catch (Throwable ex) {
        Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
        transaction.setStatus(ex);
        exception = ex;
      } finally {
        transaction.complete();
      }
      // 请求失败，sleep 等待下次重试
      try {
        m_configUtil.getOnErrorRetryIntervalTimeUnit().sleep(m_configUtil.getOnErrorRetryInterval());
      } catch (InterruptedException ex) {
        //ignore
      }
    }

    throw new ApolloConfigException(
        String.format("Get config services failed from %s", url), exception);
  }
    //设置本地缓存
  private void setConfigServices(List<ServiceDTO> services) {
    m_configServices.set(services);
    logConfigServices(services);
  }

  /**
   * 应该是获取的apollo.meta=http://127.0.0.1:8080配置
   *
   * 其实appId和ip并没有用
   * @return
   */
  private String assembleMetaServiceUrl() {
    String domainName = m_configUtil.getMetaServerDomainName();
    String appId = m_configUtil.getAppId();
    String localIp = m_configUtil.getLocalIp();

    Map<String, String> queryParams = Maps.newHashMap();
    queryParams.put("appId", queryParamEscaper.escape(appId));
    if (!Strings.isNullOrEmpty(localIp)) {
      queryParams.put("ip", queryParamEscaper.escape(localIp));
    }

    return domainName + "/services/config?" + MAP_JOINER.join(queryParams);//返回meta server提供的config service列表
  }

  private void logConfigServices(List<ServiceDTO> serviceDtos) {
    for (ServiceDTO serviceDto : serviceDtos) {
      logConfigService(serviceDto.getHomepageUrl());
    }
  }

  private void logConfigService(String serviceUrl) {
    Tracer.logEvent("Apollo.Config.Services", serviceUrl);
  }
}
