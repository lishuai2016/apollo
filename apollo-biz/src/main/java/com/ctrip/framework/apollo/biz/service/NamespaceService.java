package com.ctrip.framework.apollo.biz.service;

import com.ctrip.framework.apollo.biz.entity.Audit;
import com.ctrip.framework.apollo.biz.entity.Cluster;
import com.ctrip.framework.apollo.biz.entity.Item;
import com.ctrip.framework.apollo.biz.entity.Namespace;
import com.ctrip.framework.apollo.biz.entity.Release;
import com.ctrip.framework.apollo.biz.message.MessageSender;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.repository.NamespaceRepository;
import com.ctrip.framework.apollo.biz.utils.ReleaseMessageKeyGenerator;
import com.ctrip.framework.apollo.common.constants.GsonType;
import com.ctrip.framework.apollo.common.constants.NamespaceBranchStatus;
import com.ctrip.framework.apollo.common.entity.AppNamespace;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.exception.ServiceException;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 提供 Namespace 的 Service 逻辑给 Admin Service 和 Config Service
 */
@Service
public class NamespaceService {

  private Gson gson = new Gson();

  private final NamespaceRepository namespaceRepository;
  private final AuditService auditService;
  private final AppNamespaceService appNamespaceService;
  private final ItemService itemService;
  private final CommitService commitService;
  private final ReleaseService releaseService;
  private final ClusterService clusterService;
  private final NamespaceBranchService namespaceBranchService;
  private final ReleaseHistoryService releaseHistoryService;
  private final NamespaceLockService namespaceLockService;
  private final InstanceService instanceService;
  private final MessageSender messageSender;

  public NamespaceService(
      final ReleaseHistoryService releaseHistoryService,
      final NamespaceRepository namespaceRepository,
      final AuditService auditService,
      final @Lazy AppNamespaceService appNamespaceService,
      final MessageSender messageSender,
      final @Lazy ItemService itemService,
      final CommitService commitService,
      final @Lazy ReleaseService releaseService,
      final @Lazy ClusterService clusterService,
      final @Lazy NamespaceBranchService namespaceBranchService,
      final NamespaceLockService namespaceLockService,
      final InstanceService instanceService) {
    this.releaseHistoryService = releaseHistoryService;
    this.namespaceRepository = namespaceRepository;
    this.auditService = auditService;
    this.appNamespaceService = appNamespaceService;
    this.messageSender = messageSender;
    this.itemService = itemService;
    this.commitService = commitService;
    this.releaseService = releaseService;
    this.clusterService = clusterService;
    this.namespaceBranchService = namespaceBranchService;
    this.namespaceLockService = namespaceLockService;
    this.instanceService = instanceService;
  }


  public Namespace findOne(Long namespaceId) {
    return namespaceRepository.findById(namespaceId).orElse(null);
  }

  public Namespace findOne(String appId, String clusterName, String namespaceName) {
    return namespaceRepository.findByAppIdAndClusterNameAndNamespaceName(appId, clusterName,
                                                                         namespaceName);
  }

  public Namespace findPublicNamespaceForAssociatedNamespace(String clusterName, String namespaceName) {
    AppNamespace appNamespace = appNamespaceService.findPublicNamespaceByName(namespaceName);
    if (appNamespace == null) {
      throw new BadRequestException("namespace not exist");
    }

    String appId = appNamespace.getAppId();

    Namespace namespace = findOne(appId, clusterName, namespaceName);

    //default cluster's namespace
    if (Objects.equals(clusterName, ConfigConsts.CLUSTER_NAME_DEFAULT)) {
      return namespace;
    }

    //custom cluster's namespace not exist.
    //return default cluster's namespace
    if (namespace == null) {
      return findOne(appId, ConfigConsts.CLUSTER_NAME_DEFAULT, namespaceName);
    }

    //custom cluster's namespace exist and has published.
    //return custom cluster's namespace
    Release latestActiveRelease = releaseService.findLatestActiveRelease(namespace);
    if (latestActiveRelease != null) {
      return namespace;
    }

    Namespace defaultNamespace = findOne(appId, ConfigConsts.CLUSTER_NAME_DEFAULT, namespaceName);

    //custom cluster's namespace exist but never published.
    //and default cluster's namespace not exist.
    //return custom cluster's namespace
    if (defaultNamespace == null) {
      return namespace;
    }

    //custom cluster's namespace exist but never published.
    //and default cluster's namespace exist and has published.
    //return default cluster's namespace
    Release defaultNamespaceLatestActiveRelease = releaseService.findLatestActiveRelease(defaultNamespace);
    if (defaultNamespaceLatestActiveRelease != null) {
      return defaultNamespace;
    }

    //custom cluster's namespace exist but never published.
    //and default cluster's namespace exist but never published.
    //return custom cluster's namespace
    return namespace;
  }

  public List<Namespace> findPublicAppNamespaceAllNamespaces(String namespaceName, Pageable page) {
    AppNamespace publicAppNamespace = appNamespaceService.findPublicNamespaceByName(namespaceName);

    if (publicAppNamespace == null) {
      throw new BadRequestException(
          String.format("Public appNamespace not exists. NamespaceName = %s", namespaceName));
    }

    List<Namespace> namespaces = namespaceRepository.findByNamespaceName(namespaceName, page);

    return filterChildNamespace(namespaces);
  }

  private List<Namespace> filterChildNamespace(List<Namespace> namespaces) {
    List<Namespace> result = new LinkedList<>();

    if (CollectionUtils.isEmpty(namespaces)) {
      return result;
    }

    for (Namespace namespace : namespaces) {
      if (!isChildNamespace(namespace)) {
        result.add(namespace);
      }
    }

    return result;
  }

  public int countPublicAppNamespaceAssociatedNamespaces(String publicNamespaceName) {
    AppNamespace publicAppNamespace = appNamespaceService.findPublicNamespaceByName(publicNamespaceName);

    if (publicAppNamespace == null) {
      throw new BadRequestException(
          String.format("Public appNamespace not exists. NamespaceName = %s", publicNamespaceName));
    }

    return namespaceRepository.countByNamespaceNameAndAppIdNot(publicNamespaceName, publicAppNamespace.getAppId());
  }

  public List<Namespace> findNamespaces(String appId, String clusterName) {
    List<Namespace> namespaces = namespaceRepository.findByAppIdAndClusterNameOrderByIdAsc(appId, clusterName);
    if (namespaces == null) {
      return Collections.emptyList();
    }
    return namespaces;
  }

  public List<Namespace> findByAppIdAndNamespaceName(String appId, String namespaceName) {
    return namespaceRepository.findByAppIdAndNamespaceNameOrderByIdAsc(appId, namespaceName);
  }

  public Namespace findChildNamespace(String appId, String parentClusterName, String namespaceName) {
    //根据appid+namespaceName从namespaceName表获得数据
    // 获得 Namespace 数组
    List<Namespace> namespaces = findByAppIdAndNamespaceName(appId, namespaceName);
    //只有一个文件对应多个集群才会出现结果集大于1
    // 若只有一个 Namespace ，说明没有子 Namespace
    if (CollectionUtils.isEmpty(namespaces) || namespaces.size() == 1) {
      return null;
    }

    //获得当前集群的子集群
    // 获得 Cluster 数组
    List<Cluster> childClusters = clusterService.findChildClusters(appId, parentClusterName);
    // 若无子 Cluster ，说明没有子 Namespace
    if (CollectionUtils.isEmpty(childClusters)) {
      return null;
    }
// 创建子 Cluster 的名字的集合
    Set<String> childClusterNames = childClusters.stream().map(Cluster::getName).collect(Collectors.toSet());
    // 遍历 Namespace 数组，比较 Cluster 的名字。若符合，则返回该子 Namespace 对象。
    //the child namespace is the intersection of the child clusters and child namespaces
    for (Namespace namespace : namespaces) {
      if (childClusterNames.contains(namespace.getClusterName())) {
        return namespace;
      }
    }

    return null;
  }

  public Namespace findChildNamespace(Namespace parentNamespace) {
    String appId = parentNamespace.getAppId();
    String parentClusterName = parentNamespace.getClusterName();
    String namespaceName = parentNamespace.getNamespaceName();

    return findChildNamespace(appId, parentClusterName, namespaceName);

  }

  public Namespace findParentNamespace(String appId, String clusterName, String namespaceName) {
    return findParentNamespace(new Namespace(appId, clusterName, namespaceName));
  }

  /**
   * 查找当前namespace是否有父namespace，没有的话返回null
   * 若有父 Namespace 对象，说明是子 Namespace ( 灰度发布 )，则使用父 Namespace 的 Cluster 名字。
   * 因为，客户端即使在灰度发布的情况下，也是使用 父 Namespace 的 Cluster 名字。
   * 也就说，灰度发布，对客户端是透明无感知的
   * @param namespace
   * @return
   */
  public Namespace findParentNamespace(Namespace namespace) {
    String appId = namespace.getAppId();//应用id
    String namespaceName = namespace.getNamespaceName();//应用的名称

    Cluster cluster = clusterService.findOne(appId, namespace.getClusterName());//根据appid+集群的名称查找集群的信息
    if (cluster != null && cluster.getParentClusterId() > 0) {//集群包含父集群
      Cluster parentCluster = clusterService.findOne(cluster.getParentClusterId());
      return findOne(appId, parentCluster.getName(), namespaceName);//根据appid+集群名+namespace从namespace表获得一条记录
    }

    return null;
  }

  public boolean isChildNamespace(String appId, String clusterName, String namespaceName) {
    return isChildNamespace(new Namespace(appId, clusterName, namespaceName));
  }

  public boolean isChildNamespace(Namespace namespace) {
    return findParentNamespace(namespace) != null;
  }

  public boolean isNamespaceUnique(String appId, String cluster, String namespace) {
    Objects.requireNonNull(appId, "AppId must not be null");
    Objects.requireNonNull(cluster, "Cluster must not be null");
    Objects.requireNonNull(namespace, "Namespace must not be null");
    return Objects.isNull(
        namespaceRepository.findByAppIdAndClusterNameAndNamespaceName(appId, cluster, namespace));
  }

  @Transactional
  public void deleteByAppIdAndClusterName(String appId, String clusterName, String operator) {

    List<Namespace> toDeleteNamespaces = findNamespaces(appId, clusterName);

    for (Namespace namespace : toDeleteNamespaces) {

      deleteNamespace(namespace, operator);

    }
  }

  /**
   * 删除一个namespace对象
   *
   * @param namespace
   * @param operator
   * @return
   */
  @Transactional
  public Namespace deleteNamespace(Namespace namespace, String operator) {
    String appId = namespace.getAppId();
    String clusterName = namespace.getClusterName();
    String namespaceName = namespace.getNamespaceName();

    itemService.batchDelete(namespace.getId(), operator);//根据appid删除配置项
    commitService.batchDelete(appId, clusterName, namespace.getNamespaceName(), operator);//appid+clusterName+NamespaceName删除提交记录

    // Child namespace releases should retain as long as the parent namespace exists, because parent namespaces' release
    // histories need them   自己是不是别人的孩子
    if (!isChildNamespace(namespace)) {  //如果是灰度发布，暂时不删除，不是灰度直接删除发布信息
      releaseService.batchDelete(appId, clusterName, namespace.getNamespaceName(), operator);
    }

    //delete child namespace  看自己是不是有孩子
    Namespace childNamespace = findChildNamespace(namespace);
    if (childNamespace != null) {
      namespaceBranchService.deleteBranch(appId, clusterName, namespaceName,
                                          childNamespace.getClusterName(), NamespaceBranchStatus.DELETED, operator);
      //delete child namespace's releases. Notice: delete child namespace will not delete child namespace's releases
      releaseService.batchDelete(appId, childNamespace.getClusterName(), namespaceName, operator);
    }

    releaseHistoryService.batchDelete(appId, clusterName, namespaceName, operator);//删除发布历史

    instanceService.batchDeleteInstanceConfig(appId, clusterName, namespaceName);

    namespaceLockService.unlock(namespace.getId());

    namespace.setDeleted(true);
    namespace.setDataChangeLastModifiedBy(operator);

    auditService.audit(Namespace.class.getSimpleName(), namespace.getId(), Audit.OP.DELETE, operator);

    Namespace deleted = namespaceRepository.save(namespace);

    //Publish release message to do some clean up in config service, such as updating the cache
    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, clusterName, namespaceName),
        Topics.APOLLO_RELEASE_TOPIC);

    return deleted;
  }

  /**
   * 保存namespace到数据库
   * @param entity
   * @return
   */
  @Transactional
  public Namespace save(Namespace entity) {
    if (!isNamespaceUnique(entity.getAppId(), entity.getClusterName(), entity.getNamespaceName())) {
      throw new ServiceException("namespace not unique");
    }
    // 保护代码，避免 Namespace 对象中，已经有 id 属性。
    entity.setId(0);//protection
    Namespace namespace = namespaceRepository.save(entity);

    auditService.audit(Namespace.class.getSimpleName(), namespace.getId(), Audit.OP.INSERT,
                       namespace.getDataChangeCreatedBy());

    return namespace;
  }

  @Transactional
  public Namespace update(Namespace namespace) {
    Namespace managedNamespace = namespaceRepository.findByAppIdAndClusterNameAndNamespaceName(
        namespace.getAppId(), namespace.getClusterName(), namespace.getNamespaceName());
    BeanUtils.copyEntityProperties(namespace, managedNamespace);
    managedNamespace = namespaceRepository.save(managedNamespace);

    auditService.audit(Namespace.class.getSimpleName(), managedNamespace.getId(), Audit.OP.UPDATE,
                       managedNamespace.getDataChangeLastModifiedBy());

    return managedNamespace;
  }

  /**
   * 创建并保存 App 下指定 Cluster 的 Namespace 到数据库
   1、在 App 创建时，传入 Cluster 为 default ，此时只有 1 个 AppNamespace 对象。
   2、在 Cluster 创建时，传入自己，此处可以有多个 AppNamespace 对象。
   *
   * 简单来说就是把该appid下的所有文件关联到指定的集群clusterName上
   * @param appId
   * @param clusterName
   * @param createBy
   */
  @Transactional
  public void instanceOfAppNamespaces(String appId, String clusterName, String createBy) {

    List<AppNamespace> appNamespaces = appNamespaceService.findByAppId(appId);

    for (AppNamespace appNamespace : appNamespaces) {
      Namespace ns = new Namespace();
      ns.setAppId(appId);
      ns.setClusterName(clusterName);
      ns.setNamespaceName(appNamespace.getName());
      ns.setDataChangeCreatedBy(createBy);
      ns.setDataChangeLastModifiedBy(createBy);
      namespaceRepository.save(ns);
      auditService.audit(Namespace.class.getSimpleName(), ns.getId(), Audit.OP.INSERT, createBy);
    }

  }

  public Map<String, Boolean> namespacePublishInfo(String appId) {
    List<Cluster> clusters = clusterService.findParentClusters(appId);
    if (CollectionUtils.isEmpty(clusters)) {
      throw new BadRequestException("app not exist");
    }

    Map<String, Boolean> clusterHasNotPublishedItems = Maps.newHashMap();

    for (Cluster cluster : clusters) {
      String clusterName = cluster.getName();
      List<Namespace> namespaces = findNamespaces(appId, clusterName);

      for (Namespace namespace : namespaces) {
        boolean isNamespaceNotPublished = isNamespaceNotPublished(namespace);

        if (isNamespaceNotPublished) {
          clusterHasNotPublishedItems.put(clusterName, true);
          break;
        }
      }

      clusterHasNotPublishedItems.putIfAbsent(clusterName, false);
    }

    return clusterHasNotPublishedItems;
  }

  private boolean isNamespaceNotPublished(Namespace namespace) {

    Release latestRelease = releaseService.findLatestActiveRelease(namespace);
    long namespaceId = namespace.getId();

    if (latestRelease == null) {
      Item lastItem = itemService.findLastOne(namespaceId);
      return lastItem != null;
    }

    Date lastPublishTime = latestRelease.getDataChangeLastModifiedTime();
    List<Item> itemsModifiedAfterLastPublish = itemService.findItemsModifiedAfterDate(namespaceId, lastPublishTime);

    if (CollectionUtils.isEmpty(itemsModifiedAfterLastPublish)) {
      return false;
    }

    Map<String, String> publishedConfiguration = gson.fromJson(latestRelease.getConfigurations(), GsonType.CONFIG);
    for (Item item : itemsModifiedAfterLastPublish) {
      if (!Objects.equals(item.getValue(), publishedConfiguration.get(item.getKey()))) {
        return true;
      }
    }

    return false;
  }


}
