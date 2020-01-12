package com.ctrip.framework.apollo.configservice.service;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.repository.AppNamespaceRepository;
import com.ctrip.framework.apollo.common.entity.AppNamespace;
import com.ctrip.framework.apollo.configservice.wrapper.CaseInsensitiveMapWrapper;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.core.utils.StringUtils;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Jason Song(song_s@ctrip.com)
 * 缓存对象实例
实现 InitializingBean 接口，缓存 AppNamespace 的 Service 实现类。
通过将 AppNamespace 缓存在内存中，提高查询性能。缓存实现方式如下：

1、启动时，全量初始化 AppNamespace 到缓存
2、考虑 AppNamespace 新增，后台定时任务，定时增量初始化 AppNamespace 到缓存
3、考虑 AppNamespace 更新与删除，后台定时任务，定时全量重建 AppNamespace 到缓存

 备注：这个类的设计思路和ReleaseMessageServiceWithCache很像
 */
@Service
public class AppNamespaceServiceWithCache implements InitializingBean {
  private static final Logger logger = LoggerFactory.getLogger(AppNamespaceServiceWithCache.class);
  private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR)
      .skipNulls();
  private final AppNamespaceRepository appNamespaceRepository;
  private final BizConfig bizConfig;

  private int scanInterval;//增量初始化周期
  private TimeUnit scanIntervalTimeUnit;//增量初始化周期单位
  private int rebuildInterval;//重建周期
  private TimeUnit rebuildIntervalTimeUnit;//重建周期单位
  private ScheduledExecutorService scheduledExecutorService;
  private long maxIdScanned;//最后扫描到的 AppNamespace 的编号

  //store namespaceName -> AppNamespace  公用类型的 AppNamespace 的缓存【key:AppNamespace.name】
  private CaseInsensitiveMapWrapper<AppNamespace> publicAppNamespaceCache;//关联的namespace

  //store appId+namespaceName -> AppNamespace  App 下的 AppNamespace 的缓存【key:AppNamespace.appid+AppNamespace.name】
  private CaseInsensitiveMapWrapper<AppNamespace> appNamespaceCache;//内部包含一个map

  //store id -> AppNamespace  AppNamespace 的缓存【key:AppNamespace.id】
  private Map<Long, AppNamespace> appNamespaceIdCache;

  public AppNamespaceServiceWithCache(
      final AppNamespaceRepository appNamespaceRepository,
      final BizConfig bizConfig) {
    this.appNamespaceRepository = appNamespaceRepository;
    this.bizConfig = bizConfig;
    initialize();//构造函数中调用初始化
  }

  private void initialize() {
    maxIdScanned = 0;
    publicAppNamespaceCache = new CaseInsensitiveMapWrapper<>(Maps.newConcurrentMap());
    appNamespaceCache = new CaseInsensitiveMapWrapper<>(Maps.newConcurrentMap());
    appNamespaceIdCache = Maps.newConcurrentMap();
    scheduledExecutorService = Executors.newScheduledThreadPool(1, ApolloThreadFactory
        .create("AppNamespaceServiceWithCache", true));
  }

  public AppNamespace findByAppIdAndNamespace(String appId, String namespaceName) {
    Preconditions.checkArgument(!StringUtils.isContainEmpty(appId, namespaceName), "appId and namespaceName must not be empty");
    return appNamespaceCache.get(STRING_JOINER.join(appId, namespaceName));
  }

  public List<AppNamespace> findByAppIdAndNamespaces(String appId, Set<String> namespaceNames) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(appId), "appId must not be null");
    if (namespaceNames == null || namespaceNames.isEmpty()) {
      return Collections.emptyList();
    }
    List<AppNamespace> result = Lists.newArrayList();
    for (String namespaceName : namespaceNames) {
      AppNamespace appNamespace = appNamespaceCache.get(STRING_JOINER.join(appId, namespaceName));
      if (appNamespace != null) {
        result.add(appNamespace);
      }
    }
    return result;
  }

  public AppNamespace findPublicNamespaceByName(String namespaceName) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(namespaceName), "namespaceName must not be empty");
    return publicAppNamespaceCache.get(namespaceName);
  }

  public List<AppNamespace> findPublicNamespacesByNames(Set<String> namespaceNames) {
    if (namespaceNames == null || namespaceNames.isEmpty()) {
      return Collections.emptyList();
    }

    List<AppNamespace> result = Lists.newArrayList();
    for (String namespaceName : namespaceNames) {
      AppNamespace appNamespace = publicAppNamespaceCache.get(namespaceName);
      if (appNamespace != null) {
        result.add(appNamespace);
      }
    }
    return result;
  }
  //初始化定时任务 ，为什么会有两个定时任务？？？
  @Override
  public void afterPropertiesSet() throws Exception {
    // 从 ServerConfig 中，读取定时任务的周期配置
    populateDataBaseInterval();
    // 全量初始化 AppNamespace 缓存
    scanNewAppNamespaces(); //block the startup process until load finished
    // 创建定时任务，全量重构 AppNamespace 缓存
    scheduledExecutorService.scheduleAtFixedRate(() -> {
      Transaction transaction = Tracer.newTransaction("Apollo.AppNamespaceServiceWithCache",
          "rebuildCache");
      try {
        // 全量重建 AppNamespace 缓存
        this.updateAndDeleteCache();
        transaction.setStatus(Transaction.SUCCESS);
      } catch (Throwable ex) {
        transaction.setStatus(ex);
        logger.error("Rebuild cache failed", ex);
      } finally {
        transaction.complete();
      }
    }, rebuildInterval, rebuildInterval, rebuildIntervalTimeUnit);

    // 创建定时任务，增量初始化 AppNamespace 缓存
    scheduledExecutorService.scheduleWithFixedDelay(this::scanNewAppNamespaces, scanInterval,
        scanInterval, scanIntervalTimeUnit);
  }

  private void scanNewAppNamespaces() {
    Transaction transaction = Tracer.newTransaction("Apollo.AppNamespaceServiceWithCache",
        "scanNewAppNamespaces");
    try {
      // 加载新的 AppNamespace
      this.loadNewAppNamespaces();
      transaction.setStatus(Transaction.SUCCESS);
    } catch (Throwable ex) {
      transaction.setStatus(ex);
      logger.error("Load new app namespaces failed", ex);
    } finally {
      transaction.complete();
    }
  }

  //从数据库拉取新的 appNamespace
  //for those new app namespaces
  private void loadNewAppNamespaces() {
    boolean hasMore = true;
    // 循环，直到无新的 AppNamespace
    while (hasMore && !Thread.currentThread().isInterrupted()) {
      //current batch is 500
      // 获得大于 maxIdScanned 的 500 条 AppNamespace 记录，按照 id 升序
      List<AppNamespace> appNamespaces = appNamespaceRepository
          .findFirst500ByIdGreaterThanOrderByIdAsc(maxIdScanned);
      if (CollectionUtils.isEmpty(appNamespaces)) {
        break;
      }
      // 合并到 AppNamespace 缓存中
      mergeAppNamespaces(appNamespaces);
      int scanned = appNamespaces.size();
      maxIdScanned = appNamespaces.get(scanned - 1).getId();
      hasMore = scanned == 500;
      logger.info("Loaded {} new app namespaces with startId {}", scanned, maxIdScanned);
    }
  }

  // 合并到 AppNamespace 缓存中
  private void mergeAppNamespaces(List<AppNamespace> appNamespaces) {
    for (AppNamespace appNamespace : appNamespaces) {
      appNamespaceCache.put(assembleAppNamespaceKey(appNamespace), appNamespace);
      appNamespaceIdCache.put(appNamespace.getId(), appNamespace);
      if (appNamespace.isPublic()) {
        publicAppNamespaceCache.put(appNamespace.getName(), appNamespace);
      }
    }
  }

  //for those updated or deleted app namespaces
  private void updateAndDeleteCache() {
    // 从缓存中，获得所有的 AppNamespace 编号集合
    List<Long> ids = Lists.newArrayList(appNamespaceIdCache.keySet());
    if (CollectionUtils.isEmpty(ids)) {
      return;
    }
    // 每 500 一批，从数据库中查询最新的 AppNamespace 信息
    List<List<Long>> partitionIds = Lists.partition(ids, 500);
    for (List<Long> toRebuild : partitionIds) {
      //根据id从数据库查询
      Iterable<AppNamespace> appNamespaces = appNamespaceRepository.findAllById(toRebuild);

      if (appNamespaces == null) {
        continue;
      }

      //handle updated  更新
      Set<Long> foundIds = handleUpdatedAppNamespaces(appNamespaces);

      //handle deleted  删除
      handleDeletedAppNamespaces(Sets.difference(Sets.newHashSet(toRebuild), foundIds));
    }
  }

  //for those updated app namespaces
  private Set<Long> handleUpdatedAppNamespaces(Iterable<AppNamespace> appNamespaces) {
    Set<Long> foundIds = Sets.newHashSet();
    for (AppNamespace appNamespace : appNamespaces) {
      foundIds.add(appNamespace.getId());//其实传入的appNamespaces都设置进foundIds中了
      AppNamespace thatInCache = appNamespaceIdCache.get(appNamespace.getId());
      if (thatInCache != null && appNamespace.getDataChangeLastModifiedTime().after(thatInCache
          .getDataChangeLastModifiedTime())) {//如果当前值的修改日期大于缓存中的，更新缓存中的值
        appNamespaceIdCache.put(appNamespace.getId(), appNamespace);
        String oldKey = assembleAppNamespaceKey(thatInCache);
        String newKey = assembleAppNamespaceKey(appNamespace);
        appNamespaceCache.put(newKey, appNamespace);

        // 当 appId 或 namespaceName 发生改变的情况，将老的移除出 appNamespaceCache
        //in case appId or namespaceName changes
        if (!newKey.equals(oldKey)) {
          appNamespaceCache.remove(oldKey);
        }

        // 添加到 publicAppNamespaceCache 中
        if (appNamespace.isPublic()) {
          publicAppNamespaceCache.put(appNamespace.getName(), appNamespace);

          //in case namespaceName changes
          if (!appNamespace.getName().equals(thatInCache.getName()) && thatInCache.isPublic()) {
            publicAppNamespaceCache.remove(thatInCache.getName());
          }
        } else if (thatInCache.isPublic()) {
          //just in case isPublic changes
          publicAppNamespaceCache.remove(thatInCache.getName());
        }
        logger.info("Found AppNamespace changes, old: {}, new: {}", thatInCache, appNamespace);
      }
    }
    return foundIds;
  }

  //for those deleted app namespaces  以前缓存中存在的id现在不存在了，进行删除
  private void handleDeletedAppNamespaces(Set<Long> deletedIds) {
    if (CollectionUtils.isEmpty(deletedIds)) {
      return;
    }
    for (Long deletedId : deletedIds) {
      AppNamespace deleted = appNamespaceIdCache.remove(deletedId);
      if (deleted == null) {
        continue;
      }
      appNamespaceCache.remove(assembleAppNamespaceKey(deleted));
      if (deleted.isPublic()) {
        AppNamespace publicAppNamespace = publicAppNamespaceCache.get(deleted.getName());
        // in case there is some dirty data, e.g. public namespace deleted in some app and now created in another app
        if (publicAppNamespace == deleted) {
          publicAppNamespaceCache.remove(deleted.getName());
        }
      }
      logger.info("Found AppNamespace deleted, {}", deleted);
    }
  }
  //拼接appNamespace.getAppId()+appNamespace.getName()
  private String assembleAppNamespaceKey(AppNamespace appNamespace) {
    return STRING_JOINER.join(appNamespace.getAppId(), appNamespace.getName());
  }

  // 从 ServerConfig 中，读取定时任务的周期配置
  private void populateDataBaseInterval() {
    scanInterval = bizConfig.appNamespaceCacheScanInterval();
    scanIntervalTimeUnit = bizConfig.appNamespaceCacheScanIntervalTimeUnit();
    rebuildInterval = bizConfig.appNamespaceCacheRebuildInterval();
    rebuildIntervalTimeUnit = bizConfig.appNamespaceCacheRebuildIntervalTimeUnit();
  }

  //only for test use
  private void reset() throws Exception {
    scheduledExecutorService.shutdownNow();
    initialize();
    afterPropertiesSet();
  }
}
