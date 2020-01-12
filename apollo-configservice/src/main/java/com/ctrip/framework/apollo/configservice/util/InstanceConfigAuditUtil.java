package com.ctrip.framework.apollo.configservice.util;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;

import com.ctrip.framework.apollo.biz.entity.Instance;
import com.ctrip.framework.apollo.biz.entity.InstanceConfig;
import com.ctrip.framework.apollo.biz.service.InstanceService;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Jason Song(song_s@ctrip.com)
 * InstanceConfig 审计工具类

InstanceConfigAuditUtil 记录 Instance 和 InstanceConfig 是提交到队列，使用线程池异步处理。
auditExecutorService 属性，ExecutorService 对象。队列大小为 1 。
auditStopped 属性，是否停止。
audits 属性，队列。
 */
@Service
public class InstanceConfigAuditUtil implements InitializingBean {
  private static final int INSTANCE_CONFIG_AUDIT_MAX_SIZE = 10000;
  private static final int INSTANCE_CACHE_MAX_SIZE = 50000;
  private static final int INSTANCE_CONFIG_CACHE_MAX_SIZE = 50000;
  private static final long OFFER_TIME_LAST_MODIFIED_TIME_THRESHOLD_IN_MILLI = TimeUnit.MINUTES.toMillis(10);//10 minutes
  private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR);
  private final ExecutorService auditExecutorService;
  private final AtomicBoolean auditStopped;//是否停止
  private BlockingQueue<InstanceConfigAuditModel> audits = Queues.newLinkedBlockingQueue
      (INSTANCE_CONFIG_AUDIT_MAX_SIZE);//队列
  /**
   instanceCache 属性，Instance 的编号的缓存。其中：
   KEY ，使用 appId + clusterName + dataCenter + ip ，恰好是 Instance 的唯一索引的字段。
   VALUE ，使用 id
   */
  private Cache<String, Long> instanceCache;//Instance 的编号的缓存
  /**
   instanceConfigReleaseKeyCache 属性，InstanceConfig 的 ReleaseKey 的缓存。其中：
   KEY ，使用 instanceId + configAppId + ConfigNamespaceName ，恰好是 InstanceConfig 的唯一索引的字段。
   VALUE ，使用 releaseKey
   */
  private Cache<String, String> instanceConfigReleaseKeyCache;//InstanceConfig 的 ReleaseKey 的缓存

  private final InstanceService instanceService;

  public InstanceConfigAuditUtil(final InstanceService instanceService) {
    this.instanceService = instanceService;
    auditExecutorService = Executors.newSingleThreadExecutor(
        ApolloThreadFactory.create("InstanceConfigAuditUtil", true));
    auditStopped = new AtomicBoolean(false);
    instanceCache = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.HOURS)
        .maximumSize(INSTANCE_CACHE_MAX_SIZE).build();
    instanceConfigReleaseKeyCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.DAYS)
        .maximumSize(INSTANCE_CONFIG_CACHE_MAX_SIZE).build();
  }

  //封装成InstanceConfigAuditModel添加到队列中去
  public boolean audit(String appId, String clusterName, String dataCenter, String
      ip, String configAppId, String configClusterName, String configNamespace, String releaseKey) {
    return this.audits.offer(new InstanceConfigAuditModel(appId, clusterName, dataCenter, ip,
        configAppId, configClusterName, configNamespace, releaseKey));
  }

  void doAudit(InstanceConfigAuditModel auditModel) {
    // appId + clusterName + dataCenter + ip 唯一标识instance
    String instanceCacheKey = assembleInstanceKey(auditModel.getAppId(), auditModel
        .getClusterName(), auditModel.getIp(), auditModel.getDataCenter());
    //从缓存中拿id
    Long instanceId = instanceCache.getIfPresent(instanceCacheKey);
    //缓存中不存在，从数据库中查询，数据库中没有插入数据库然后返回
    if (instanceId == null) {
      instanceId = prepareInstanceId(auditModel);
      instanceCache.put(instanceCacheKey, instanceId);
    }

    //load instance config release key from cache, and check if release key is the same
    //instanceId + configAppId + ConfigNamespaceName唯一标识instanceConfig实例
    String instanceConfigCacheKey = assembleInstanceConfigKey(instanceId, auditModel
        .getConfigAppId(), auditModel.getConfigNamespace());
    String cacheReleaseKey = instanceConfigReleaseKeyCache.getIfPresent(instanceConfigCacheKey);

    //if release key is the same, then skip audit 缓存中有直接跳过audit
    if (cacheReleaseKey != null && Objects.equals(cacheReleaseKey, auditModel.getReleaseKey())) {
      return;
    }
    // 更新对应的 instanceConfigReleaseKeyCache 缓存
    instanceConfigReleaseKeyCache.put(instanceConfigCacheKey, auditModel.getReleaseKey());

    //if release key is not the same or cannot find in cache, then do audit
    // 如果发布key不一致或者缓存中没有，需要audit
    //通过instanceId + configAppId + ConfigNamespaceName从数据库查询
    InstanceConfig instanceConfig = instanceService.findInstanceConfig(instanceId, auditModel
        .getConfigAppId(), auditModel.getConfigNamespace());
    //已经存在 若 InstanceConfig 已经存在，进行更新
    if (instanceConfig != null) {
      // ReleaseKey 发生变化
      if (!Objects.equals(instanceConfig.getReleaseKey(), auditModel.getReleaseKey())) {
        instanceConfig.setConfigClusterName(auditModel.getConfigClusterName());
        instanceConfig.setReleaseKey(auditModel.getReleaseKey());
        instanceConfig.setReleaseDeliveryTime(auditModel.getOfferTime());// 配置下发时间，使用入队时间
        // 时间过近，例如 Client 先请求的 Config Service A 节点，再请求 Config Service B 节点的情况。
        // 此时，InstanceConfig 在 DB 中是已经更新了，但是在 Config Service B 节点的缓存是未更新的
      } else if (offerTimeAndLastModifiedTimeCloseEnough(auditModel.getOfferTime(),
          instanceConfig.getDataChangeLastModifiedTime())) {
        //when releaseKey is the same, optimize to reduce writes if the record was updated not long ago
        return;
      }
      //we need to update no matter the release key is the same or not, to ensure the
      //last modified time is updated each day
      instanceConfig.setDataChangeLastModifiedTime(auditModel.getOfferTime());
      instanceService.updateInstanceConfig(instanceConfig);//更新数据库
      return;
    }
    //不存在新建保存到数据库
    instanceConfig = new InstanceConfig();
    instanceConfig.setInstanceId(instanceId);
    instanceConfig.setConfigAppId(auditModel.getConfigAppId());
    instanceConfig.setConfigClusterName(auditModel.getConfigClusterName());
    instanceConfig.setConfigNamespaceName(auditModel.getConfigNamespace());
    instanceConfig.setReleaseKey(auditModel.getReleaseKey());
    instanceConfig.setReleaseDeliveryTime(auditModel.getOfferTime());
    instanceConfig.setDataChangeCreatedTime(auditModel.getOfferTime());

    try {
      instanceService.createInstanceConfig(instanceConfig);
    } catch (DataIntegrityViolationException ex) {
      //concurrent insertion, safe to ignore
    }
  }

  private boolean offerTimeAndLastModifiedTimeCloseEnough(Date offerTime, Date lastModifiedTime) {
    return (offerTime.getTime() - lastModifiedTime.getTime()) <
        OFFER_TIME_LAST_MODIFIED_TIME_THRESHOLD_IN_MILLI;
  }

  private long prepareInstanceId(InstanceConfigAuditModel auditModel) {
    //从数据库中查询
    Instance instance = instanceService.findInstance(auditModel.getAppId(), auditModel
        .getClusterName(), auditModel.getDataCenter(), auditModel.getIp());
    if (instance != null) {
      return instance.getId();
    }
    //没有的话，创建插入数据库
    instance = new Instance();
    instance.setAppId(auditModel.getAppId());
    instance.setClusterName(auditModel.getClusterName());
    instance.setDataCenter(auditModel.getDataCenter());
    instance.setIp(auditModel.getIp());


    try {
      return instanceService.createInstance(instance).getId();
    } catch (DataIntegrityViolationException ex) {
      // 发生唯一索引冲突，意味着已经存在，进行查询 Instance 对象，并返回
      //return the one exists
      return instanceService.findInstance(instance.getAppId(), instance.getClusterName(),
          instance.getDataCenter(), instance.getIp()).getId();
    }
  }

  //初始化任务 从阻塞队列中拉取任务然后执行，拉取不到休眠一会继续
  @Override
  public void afterPropertiesSet() throws Exception {
    auditExecutorService.submit(() -> {
      while (!auditStopped.get() && !Thread.currentThread().isInterrupted()) {
        try {
          InstanceConfigAuditModel model = audits.poll();
          if (model == null) {
            TimeUnit.SECONDS.sleep(1);
            continue;
          }
          doAudit(model);// 若获取到，记录 Instance 和 InstanceConfig
        } catch (Throwable ex) {
          Tracer.logError(ex);
        }
      }
    });
  }

  //拼接
  private String assembleInstanceKey(String appId, String cluster, String ip, String datacenter) {
    List<String> keyParts = Lists.newArrayList(appId, cluster, ip);
    if (!Strings.isNullOrEmpty(datacenter)) {
      keyParts.add(datacenter);
    }
    return STRING_JOINER.join(keyParts);
  }
  //拼接
  private String assembleInstanceConfigKey(long instanceId, String configAppId, String configNamespace) {
    return STRING_JOINER.join(instanceId, configAppId, configNamespace);
  }

  public static class InstanceConfigAuditModel {
    private String appId;
    private String clusterName;
    private String dataCenter;
    private String ip;
    private String configAppId;
    private String configClusterName;
    private String configNamespace;
    private String releaseKey;
    private Date offerTime;

    public InstanceConfigAuditModel(String appId, String clusterName, String dataCenter, String
        clientIp, String configAppId, String configClusterName, String configNamespace, String
                                        releaseKey) {
      this.offerTime = new Date();
      this.appId = appId;
      this.clusterName = clusterName;
      this.dataCenter = Strings.isNullOrEmpty(dataCenter) ? "" : dataCenter;
      this.ip = clientIp;
      this.configAppId = configAppId;
      this.configClusterName = configClusterName;
      this.configNamespace = configNamespace;
      this.releaseKey = releaseKey;
    }

    public String getAppId() {
      return appId;
    }

    public String getClusterName() {
      return clusterName;
    }

    public String getDataCenter() {
      return dataCenter;
    }

    public String getIp() {
      return ip;
    }

    public String getConfigAppId() {
      return configAppId;
    }

    public String getConfigNamespace() {
      return configNamespace;
    }

    public String getReleaseKey() {
      return releaseKey;
    }

    public String getConfigClusterName() {
      return configClusterName;
    }

    public Date getOfferTime() {
      return offerTime;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      InstanceConfigAuditModel model = (InstanceConfigAuditModel) o;
      return Objects.equals(appId, model.appId) &&
          Objects.equals(clusterName, model.clusterName) &&
          Objects.equals(dataCenter, model.dataCenter) &&
          Objects.equals(ip, model.ip) &&
          Objects.equals(configAppId, model.configAppId) &&
          Objects.equals(configClusterName, model.configClusterName) &&
          Objects.equals(configNamespace, model.configNamespace) &&
          Objects.equals(releaseKey, model.releaseKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(appId, clusterName, dataCenter, ip, configAppId, configClusterName,
          configNamespace,
          releaseKey);
    }
  }
}
