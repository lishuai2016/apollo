package com.ctrip.framework.apollo.metaservice.controller;

import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.metaservice.service.DiscoveryService;
import com.netflix.appinfo.InstanceInfo;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 1、提供了三个 API ，services/meta、services/config、services/admin
 获得 Meta Service、Config Service、Admin Service 集群地址。😈 实际上，
 services/meta 暂时是不可用的，获取不到实例，因为 Meta Service 目前内嵌在 Config Service 中。

 2、在每个 API 中，调用 DiscoveryService 调用对应的方法，获取服务集群。

 总结：其实meta server很简单，就是通过注册中心来获得各个服务的地址。
 */

@RestController
@RequestMapping("/services")
public class ServiceController {

  private final DiscoveryService discoveryService;

  private static Function<InstanceInfo, ServiceDTO> instanceInfoToServiceDTOFunc = instance -> {
    ServiceDTO service = new ServiceDTO();
    service.setAppName(instance.getAppName());
    service.setInstanceId(instance.getInstanceId());
    service.setHomepageUrl(instance.getHomePageUrl());
    return service;
  };

  public ServiceController(final DiscoveryService discoveryService) {
    this.discoveryService = discoveryService;
  }


  @RequestMapping("/meta")
  public List<ServiceDTO> getMetaService() {
    List<InstanceInfo> instances = discoveryService.getMetaServiceInstances();
    List<ServiceDTO> result = instances.stream().map(instanceInfoToServiceDTOFunc).collect(Collectors.toList());
    return result;
  }

  /**
   *
   ping 测试地址是否可用，返回的内容格式：
   [
   {
   appName: "APOLLO-CONFIGSERVICE",
   instanceId: "host-10-181-163-3:apollo-configservice:80",
   homepageUrl: "http://10.181.163.3:80/"
   }
   ]
   *
   * @param appId
   * @param clientIp
   * @return
   */
  @RequestMapping("/config")
  public List<ServiceDTO> getConfigService(
      @RequestParam(value = "appId", defaultValue = "") String appId,
      @RequestParam(value = "ip", required = false) String clientIp) {
    List<InstanceInfo> instances = discoveryService.getConfigServiceInstances();
    List<ServiceDTO> result = instances.stream().map(instanceInfoToServiceDTOFunc).collect(Collectors.toList());
    return result;
  }

  @RequestMapping("/admin")
  public List<ServiceDTO> getAdminService() {
    List<InstanceInfo> instances = discoveryService.getAdminServiceInstances();
    List<ServiceDTO> result = instances.stream().map(instanceInfoToServiceDTOFunc).collect(Collectors.toList());
    return result;
  }
}
