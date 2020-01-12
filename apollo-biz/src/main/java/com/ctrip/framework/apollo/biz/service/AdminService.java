package com.ctrip.framework.apollo.biz.service;

import com.ctrip.framework.apollo.biz.entity.Cluster;
import com.ctrip.framework.apollo.common.entity.App;
import com.ctrip.framework.apollo.core.ConfigConsts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 封装了创建APP以及删除APP调用多个服务的操作
 */

@Service
public class AdminService {
  private final static Logger logger = LoggerFactory.getLogger(AdminService.class);

  private final AppService appService;
  private final AppNamespaceService appNamespaceService;
  private final ClusterService clusterService;
  private final NamespaceService namespaceService;

  public AdminService(
      final AppService appService,
      final @Lazy AppNamespaceService appNamespaceService,
      final @Lazy ClusterService clusterService,
      final @Lazy NamespaceService namespaceService) {
    this.appService = appService;
    this.appNamespaceService = appNamespaceService;
    this.clusterService = clusterService;
    this.namespaceService = namespaceService;
  }

  @Transactional
  public App createNewApp(App app) {
    String createBy = app.getDataChangeCreatedBy();
    App createdApp = appService.save(app);//保存到数据库

    String appId = createdApp.getAppId();
// 创建 App 的默认命名空间 "application"
    appNamespaceService.createDefaultAppNamespace(appId, createBy);//创建默认的appnamespace，保存到数据库
    // 创建 App 的默认集群 "default"
    clusterService.createDefaultCluster(appId, createBy);//创建默认的集群
// 创建 Cluster 的默认命名空间
    namespaceService.instanceOfAppNamespaces(appId, ConfigConsts.CLUSTER_NAME_DEFAULT, createBy);//创建默认的namespace保存到数据库

    return app;
  }

  @Transactional
  public void deleteApp(App app, String operator) {
    String appId = app.getAppId();

    logger.info("{} is deleting App:{}", operator, appId);

    List<Cluster> managedClusters = clusterService.findClusters(appId);//找到这个appid下的集群

    // 1. delete clusters
    if (Objects.nonNull(managedClusters)) {
      for (Cluster cluster : managedClusters) {
        clusterService.delete(cluster.getId(), operator);
      }
    }

    // 2. delete appNamespace 通过appid找到关联的配置文件，然后删除
    appNamespaceService.batchDelete(appId, operator);

    // 3. delete app
    appService.delete(app.getId(), operator);
  }
}
