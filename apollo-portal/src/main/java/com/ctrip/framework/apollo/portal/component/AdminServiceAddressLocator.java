package com.ctrip.framework.apollo.portal.component;

import com.ctrip.framework.apollo.core.MetaDomainConsts;
import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 Admin Service 定位器。

 1、初始时，创建延迟 1 秒的任务，从 Meta Service 获取 Config Service 集群地址进行缓存。

 2、获取成功时，创建延迟 5 分钟的任务，从 Meta Service 获取 Config Service 集群地址刷新缓存。

 3、获取失败时，创建延迟 10 秒的任务，从 Meta Service 获取 Config Service 集群地址刷新缓存。

 */


@Component
public class AdminServiceAddressLocator {

  private static final long NORMAL_REFRESH_INTERVAL = 5 * 60 * 1000;
  private static final long OFFLINE_REFRESH_INTERVAL = 10 * 1000;
  private static final int RETRY_TIMES = 3;
  private static final String ADMIN_SERVICE_URL_PATH = "/services/admin";
  private static final Logger logger = LoggerFactory.getLogger(AdminServiceAddressLocator.class);

  private ScheduledExecutorService refreshServiceAddressService;
  private RestTemplate restTemplate;
  private List<Env> allEnvs;
  private Map<Env, List<ServiceDTO>> cache = new ConcurrentHashMap<>();//本地的缓存

  private final PortalSettings portalSettings;
  private final RestTemplateFactory restTemplateFactory;

  public AdminServiceAddressLocator(
      final HttpMessageConverters httpMessageConverters,
      final PortalSettings portalSettings,
      final RestTemplateFactory restTemplateFactory) {
    this.portalSettings = portalSettings;
    this.restTemplateFactory = restTemplateFactory;
  }

  @PostConstruct
  public void init() {
    allEnvs = portalSettings.getAllEnvs();//从配置表中获得环境列表

    //init restTemplate
    restTemplate = restTemplateFactory.getObject();
    // 创建 ScheduledExecutorService
    refreshServiceAddressService =
        Executors.newScheduledThreadPool(1, ApolloThreadFactory.create("ServiceLocator", true));
    // 创建延迟任务，1 秒后拉取 Admin Service 地址
    refreshServiceAddressService.schedule(new RefreshAdminServerAddressTask(), 1, TimeUnit.MILLISECONDS);
  }


  public List<ServiceDTO> getServiceList(Env env) {
    // 从缓存中获得 ServiceDTO 数组
    List<ServiceDTO> services = cache.get(env);
    // 若不存在，直接返回空数组。这点和 ConfigServiceLocator 不同。
    if (CollectionUtils.isEmpty(services)) {
      return Collections.emptyList();
    }
    // 打乱 ServiceDTO 数组，返回。实现 Client 级的负载均衡
    List<ServiceDTO> randomConfigServices = Lists.newArrayList(services);
    Collections.shuffle(randomConfigServices);
    return randomConfigServices;
  }

  //maintain admin server address
  private class RefreshAdminServerAddressTask implements Runnable {

    @Override
    public void run() {
      boolean refreshSuccess = true;
      //refresh fail if get any env address fail
      // 循环多个 Env ，请求对应的 Meta Service ，获得 Admin Service 集群地址
      for (Env env : allEnvs) {
        boolean currentEnvRefreshResult = refreshServerAddressCache(env);//更新本地缓存
        refreshSuccess = refreshSuccess && currentEnvRefreshResult;
      }

      if (refreshSuccess) {//创建五分钟的延时任务
        refreshServiceAddressService
            .schedule(new RefreshAdminServerAddressTask(), NORMAL_REFRESH_INTERVAL, TimeUnit.MILLISECONDS);
      } else { //创建10秒的延迟任务
        refreshServiceAddressService
            .schedule(new RefreshAdminServerAddressTask(), OFFLINE_REFRESH_INTERVAL, TimeUnit.MILLISECONDS);
      }
    }
  }

  /**
   * 具体的刷新admin 服务的方法
   * @param env
   * @return
   */
  private boolean refreshServerAddressCache(Env env) {

    for (int i = 0; i < RETRY_TIMES; i++) {//循环重试

      try {
        // 请求 Meta Service ，获得 Admin Service 集群地址
        ServiceDTO[] services = getAdminServerAddress(env);
        if (services == null || services.length == 0) {
          continue;
        }
        cache.put(env, Arrays.asList(services));
        return true;
      } catch (Throwable e) {
        logger.error(String.format("Get admin server address from meta server failed. env: %s, meta server address:%s",
                                   env, MetaDomainConsts.getDomain(env)), e);
        Tracer
            .logError(String.format("Get admin server address from meta server failed. env: %s, meta server address:%s",
                                    env, MetaDomainConsts.getDomain(env)), e);
      }
    }
    return false;
  }

  /**
   * 发送http请求获取结果的方法
   * @param env
   * @return
   */
  private ServiceDTO[] getAdminServerAddress(Env env) {
    String domainName = MetaDomainConsts.getDomain(env);
    String url = domainName + ADMIN_SERVICE_URL_PATH;
    return restTemplate.getForObject(url, ServiceDTO[].class);
  }


}
