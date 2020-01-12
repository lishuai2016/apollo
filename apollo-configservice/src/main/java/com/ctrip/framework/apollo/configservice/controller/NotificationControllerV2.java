package com.ctrip.framework.apollo.configservice.controller;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.message.ReleaseMessageListener;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.utils.EntityManagerUtil;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.configservice.service.ReleaseMessageServiceWithCache;
import com.ctrip.framework.apollo.configservice.util.NamespaceUtil;
import com.ctrip.framework.apollo.configservice.util.WatchKeysUtil;
import com.ctrip.framework.apollo.configservice.wrapper.DeferredResultWrapper;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.dto.ApolloConfigNotification;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Jason Song(song_s@ctrip.com)
 *

Config Service 通知配置变化：

1、客户端会发起一个Http 请求到 Config Service 的 notifications/v2 接口，
也就是NotificationControllerV2 ，参见 RemoteConfigLongPollService 。

2、NotificationControllerV2 不会立即返回结果，而是通过 Spring DeferredResult 把请求挂起。

3、如果在 60 秒内没有该客户端关心的配置发布，那么会返回 Http 状态码 304 给客户端。

关心的配置格式？？？如何唯一标识这个唯一的namespace文件？？？

4、如果有该客户端关心的配置发布，NotificationControllerV2 会调用 DeferredResult 的 setResult 方法，
传入有配置变化的 namespace 信息，同时该请求会立即返回。客户端从返回的结果中获取到配置变化的 namespace 后，
会立即请求 Config Service 获取该 namespace 的最新配置。



 */
@RestController
@RequestMapping("/notifications/v2")
public class NotificationControllerV2 implements ReleaseMessageListener {
  private static final Logger logger = LoggerFactory.getLogger(NotificationControllerV2.class);
  //Watch Key 与 DeferredResultWrapper 的 Multimap,Watch Key 等价于 ReleaseMessage 的通知内容 message 字段。 比如：SampleApp+default+application
  private final Multimap<String, DeferredResultWrapper> deferredResults =
      Multimaps.synchronizedSetMultimap(HashMultimap.create());
  //按照+号来分割字符串
  private static final Splitter STRING_SPLITTER =
      Splitter.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR).omitEmptyStrings();

  private static final Type notificationsTypeReference =
      new TypeToken<List<ApolloConfigNotification>>() {
      }.getType();

  //大量通知分批执行 ExecutorService
  private final ExecutorService largeNotificationBatchExecutorService;

  private final WatchKeysUtil watchKeysUtil;
  private final ReleaseMessageServiceWithCache releaseMessageService;
  private final EntityManagerUtil entityManagerUtil;
  private final NamespaceUtil namespaceUtil;
  private final Gson gson;
  private final BizConfig bizConfig;

  @Autowired
  public NotificationControllerV2(
      final WatchKeysUtil watchKeysUtil,
      final ReleaseMessageServiceWithCache releaseMessageService,
      final EntityManagerUtil entityManagerUtil,
      final NamespaceUtil namespaceUtil,
      final Gson gson,
      final BizConfig bizConfig) {
    largeNotificationBatchExecutorService = Executors.newSingleThreadExecutor(ApolloThreadFactory.create
        ("NotificationControllerV2", true));
    this.watchKeysUtil = watchKeysUtil;
    this.releaseMessageService = releaseMessageService;
    this.entityManagerUtil = entityManagerUtil;
    this.namespaceUtil = namespaceUtil;
    this.gson = gson;
    this.bizConfig = bizConfig;
  }

  /**
   * 唯一的接口
   *
   *
   *
   *
   *
   * @param appId
   * @param cluster
   * @param notificationsAsString
   * @param dataCenter
   * @param clientIp
   * @return
   */
  @GetMapping
  public DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> pollNotification(
      @RequestParam(value = "appId") String appId,
      @RequestParam(value = "cluster") String cluster,
      @RequestParam(value = "notifications") String notificationsAsString,
      @RequestParam(value = "dataCenter", required = false) String dataCenter,
      @RequestParam(value = "ip", required = false) String clientIp) {

    /**
     * 解析 notificationsAsString 参数，创建 ApolloConfigNotification 数组。因为一个客户端可以订阅多个 Namespace ，所以该参数是 List
     * 客户端请求时，只传递 ApolloConfigNotification 的 namespaceName + notificationId ，不传递 messages
     */
    List<ApolloConfigNotification> notifications = null;

    try {
      notifications =
          gson.fromJson(notificationsAsString, notificationsTypeReference);
    } catch (Throwable ex) {
      Tracer.logError(ex);
    }

    if (CollectionUtils.isEmpty(notifications)) {
      throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
    }
    //创建 DeferredResultWrapper 对象【返回的结果】
    DeferredResultWrapper deferredResultWrapper = new DeferredResultWrapper();
    //创建 Namespace 的名字的集合
    Set<String> namespaces = Sets.newHashSet();
    //创建客户端的通知信息 Map 。其中，KEY 为 Namespace 的名字，VALUE 为通知编号。
    Map<String, Long> clientSideNotifications = Maps.newHashMap();
    //过滤并创建 ApolloConfigNotification Map，key 是namespace，value是ApolloConfigNotification
    Map<String, ApolloConfigNotification> filteredNotifications = filterNotifications(appId, notifications);
    //遍历这个map处理
    for (Map.Entry<String, ApolloConfigNotification> notificationEntry : filteredNotifications.entrySet()) {
      String normalizedNamespace = notificationEntry.getKey();
      ApolloConfigNotification notification = notificationEntry.getValue();

      namespaces.add(normalizedNamespace);//添加到 namespaces 中
      clientSideNotifications.put(normalizedNamespace, notification.getNotificationId());//添加到 clientSideNotifications 中
      if (!Objects.equals(notification.getNamespaceName(), normalizedNamespace)) {
        //记录名字被归一化的 Namespace 。因为，最终返回给客户端，使用原始的 Namespace 名字，否则客户端无法识别
        deferredResultWrapper.recordNamespaceNameNormalizedResult(notification.getNamespaceName(), normalizedNamespace);
      }
    }

    if (CollectionUtils.isEmpty(namespaces)) {
      throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
    }
    //依据namespace为key,value为：appId+cluster+namespace构建map
    Multimap<String, String> watchedKeysMap =
        watchKeysUtil.assembleAllWatchKeys(appId, cluster, namespaces, dataCenter);
    //生成 Watch Key 集合
    Set<String> watchedKeys = Sets.newHashSet(watchedKeysMap.values());

    /**
     * 1、set deferredResult before the check, for avoid more waiting
     * If the check before setting deferredResult,it may receive a notification the next time
     * when method handleMessage is executed between check and set deferredResult.
     */
    // 注册超时事件
    deferredResultWrapper
          .onTimeout(() -> logWatchedKeys(watchedKeys, "Apollo.LongPoll.TimeOutKeys"));
    // 注册结束事件，结束移除监听器
    deferredResultWrapper.onCompletion(() -> {
      // 移除 Watch Key + DeferredResultWrapper 出 `deferredResults`
      //unregister all keys
      for (String key : watchedKeys) {
        deferredResults.remove(key, deferredResultWrapper);
      }
      // Tracer 日志
      logWatchedKeys(watchedKeys, "Apollo.LongPoll.CompletedKeys");
    });

    // 注册 Watch Key + DeferredResultWrapper 到 `deferredResults` 中，等待配置发生变化后通知.详见 `#handleMessage(...)` 方法。
    // 这样，任意一个 Watch Key 对应的 Namespace 对应的配置发生变化时，都可以进行通知，并结束轮询等待。
    //register all keys
    for (String key : watchedKeys) {
      this.deferredResults.put(key, deferredResultWrapper);
    }

    logWatchedKeys(watchedKeys, "Apollo.LongPoll.RegisteredKeys");
    logger.debug("Listening {} from appId: {}, cluster: {}, namespace: {}, datacenter: {}",
        watchedKeys, appId, cluster, namespaces, dataCenter);

    /**
     * 2、check new release
     */
    // 获得 Watch Key 集合中，每个 Watch Key 对应的 ReleaseMessage 记录
    List<ReleaseMessage> latestReleaseMessages =
        releaseMessageService.findLatestReleaseMessagesGroupByMessages(watchedKeys);

    /**
     * Manually close the entity manager.
     * Since for async request, Spring won't do so until the request is finished,
     * which is unacceptable since we are doing long polling - means the db connection would be hold
     * for a very long time
     *手动关闭 EntityManager
     *因为对于 async 请求，Spring 在请求完成之前不会这样做
     *这是不可接受的，因为我们正在做长轮询——意味着 db 连接将被保留很长时间
     *实际上，下面的过程，我们已经不需要 db 连接，因此进行关闭
     */
    entityManagerUtil.closeEntityManager();
    // 获得新的 ApolloConfigNotification 通知数组
    List<ApolloConfigNotification> newNotifications =
        getApolloConfigNotifications(namespaces, clientSideNotifications, watchedKeysMap,
            latestReleaseMessages);
  // 若有新的通知，调用 DeferredResultWrapper#setResult(List<ApolloConfigNotification>) 方法，直接设置 DeferredResult 的结果，从而结束长轮询。
    if (!CollectionUtils.isEmpty(newNotifications)) {
      deferredResultWrapper.setResult(newNotifications);
    }

    return deferredResultWrapper.getResult();
  }

  /**
   * 这个方法的逻辑比较“绕”，目的是客户端传递的 Namespace 的名字不是正确的，例如大小写不对，需要做下归一化( normalized )处理。
   * @param appId
   * @param notifications
   * @return
   */
  private Map<String, ApolloConfigNotification> filterNotifications(String appId,
                                                                    List<ApolloConfigNotification> notifications) {
    //其中，KEY 为 Namespace 的名字
    Map<String, ApolloConfigNotification> filteredNotifications = Maps.newHashMap();
    for (ApolloConfigNotification notification : notifications) {
      if (Strings.isNullOrEmpty(notification.getNamespaceName())) {
        continue;
      }
      //strip out .properties suffix 去除后缀
      String originalNamespace = namespaceUtil.filterNamespaceName(notification.getNamespaceName());
      notification.setNamespaceName(originalNamespace);
      //fix the character case issue, such as FX.apollo <-> fx.apollo 名称大小写归一化
      String normalizedNamespace = namespaceUtil.normalizeNamespace(appId, originalNamespace);

      // in case client side namespace name has character case issue and has difference notification ids
      // such as FX.apollo = 1 but fx.apollo = 2, we should let FX.apollo have the chance to update its notification id
      // which means we should record FX.apollo = 1 here and ignore fx.apollo = 2
      //这里的含义是，因大小写造成的名字不统一问题，忽略大的编号？？？
      if (filteredNotifications.containsKey(normalizedNamespace) &&
          filteredNotifications.get(normalizedNamespace).getNotificationId() < notification.getNotificationId()) {
        continue;
      }

      filteredNotifications.put(normalizedNamespace, notification);
    }
    return filteredNotifications;
  }

  /**
   *
   * @param namespaces
   * @param clientSideNotifications KEY 为 Namespace 的名字，VALUE 为通知编号
   * @param watchedKeysMap  据namespace为key,value为：appId+cluster+namespace构建map
   * @param latestReleaseMessages 表示最近发布的信息ReleaseMessage数据库表的映射    ，这里对象是上面两个map的value包含的数据
   * @return
   */
  private List<ApolloConfigNotification> getApolloConfigNotifications(Set<String> namespaces,
                                                                      Map<String, Long> clientSideNotifications,
                                                                      Multimap<String, String> watchedKeysMap,
                                                                      List<ReleaseMessage> latestReleaseMessages) {
    List<ApolloConfigNotification> newNotifications = Lists.newArrayList();
    if (!CollectionUtils.isEmpty(latestReleaseMessages)) {
      Map<String, Long> latestNotifications = Maps.newHashMap();
      for (ReleaseMessage releaseMessage : latestReleaseMessages) {
        latestNotifications.put(releaseMessage.getMessage(), releaseMessage.getId());//读出ReleaseMessage映射到map中
      }

      for (String namespace : namespaces) { //这个namespace只是文件的名称
        long clientSideId = clientSideNotifications.get(namespace);//获得这个文件的客户端版本
        long latestId = ConfigConsts.NOTIFICATION_ID_PLACEHOLDER;
        Collection<String> namespaceWatchedKeys = watchedKeysMap.get(namespace);//拿到这个文件对应的唯一标识集合
        //获得这个namespace对应最新版本号
        for (String namespaceWatchedKey : namespaceWatchedKeys) {
          long namespaceNotificationId =
              latestNotifications.getOrDefault(namespaceWatchedKey, ConfigConsts.NOTIFICATION_ID_PLACEHOLDER);
          if (namespaceNotificationId > latestId) {
            latestId = namespaceNotificationId;
          }
        }
        //说明有更新
        if (latestId > clientSideId) {
          ApolloConfigNotification notification = new ApolloConfigNotification(namespace, latestId);
          namespaceWatchedKeys.stream().filter(latestNotifications::containsKey).forEach(namespaceWatchedKey ->
              notification.addMessage(namespaceWatchedKey, latestNotifications.get(namespaceWatchedKey)));
          newNotifications.add(notification);
        }
      }
    }
    return newNotifications;
  }


  /**
   *  监听器的回调接口
   * 当有新的 ReleaseMessage 时，通知其对应的 Namespace 的，并且正在等待的请求
   *
   * 这个监听器接口是如何工作的？？？
   *
   *
   假设一个公共 Namespace 有10W 台机器使用，如果该公共 Namespace 发布时直接下发配置更新消息的话，
   就会导致这 10W 台机器一下子都来请求配置，这动静就有点大了，而且对 Config Service 的压力也会比较大。

   数量可通过 ServerConfig "apollo.release-message.notification.batch" 配置，默认 100 。
   每通知 "apollo.release-message.notification.batch" 个客户端，sleep 一段时间。可通过 ServerConfig "apollo.release-message.notification.batch.interval" 配置，默认 100 毫秒。
   调用 DeferredResultWrapper#setResult(List<ApolloConfigNotification>) 方法，设置 DeferredResult 的结果，从而结束长轮询。

   * @param message
   * @param channel
   */
  @Override
  public void handleMessage(ReleaseMessage message, String channel) {
    logger.info("message received - channel: {}, message: {}", channel, message);

    String content = message.getMessage();//配置文件的唯一标识，格式appId+cluster+namespace
    Tracer.logEvent("Apollo.LongPoll.Messages", content);
    // 仅处理 APOLLO_RELEASE_TOPIC
    if (!Topics.APOLLO_RELEASE_TOPIC.equals(channel) || Strings.isNullOrEmpty(content)) {
      return;
    }

    String changedNamespace = retrieveNamespaceFromReleaseMessage.apply(content);//获得变化的Namespace

    if (Strings.isNullOrEmpty(changedNamespace)) {
      logger.error("message format invalid - {}", content);
      return;
    }

    if (!deferredResults.containsKey(content)) {//没有人请求这个文件，直接返回
      return;
    }

    //create a new list to avoid ConcurrentModificationException
    List<DeferredResultWrapper> results = Lists.newArrayList(deferredResults.get(content));//获得监听这个变化Namespace的请求列表

    ApolloConfigNotification configNotification = new ApolloConfigNotification(changedNamespace, message.getId());
    configNotification.addMessage(content, message.getId());//封装返回的通知对象

    //do async notification if too many clients    默认100个，如果客户端的链接太多异步通知
    if (results.size() > bizConfig.releaseMessageNotificationBatch()) {
      largeNotificationBatchExecutorService.submit(() -> {
        logger.debug("Async notify {} clients for key {} with batch {}", results.size(), content,
            bizConfig.releaseMessageNotificationBatch());
        for (int i = 0; i < results.size(); i++) {
          if (i > 0 && i % bizConfig.releaseMessageNotificationBatch() == 0) {
            // 每 N 个客户端，sleep 一段时间。
            try {
              TimeUnit.MILLISECONDS.sleep(bizConfig.releaseMessageNotificationBatchIntervalInMilli());
            } catch (InterruptedException e) {
              //ignore
            }
          }
          logger.debug("Async notify {}", results.get(i));
          results.get(i).setResult(configNotification);
        }
      });
      return;
    }

    logger.debug("Notify {} clients for key {}", results.size(), content);

    for (DeferredResultWrapper result : results) {
      result.setResult(configNotification);//设置返回的结果
    }
    logger.debug("Notification completed");
  }
  // 通过 ReleaseMessage 的消息内容，获得对应 Namespace 的名字, 比如：SampleApp+default+application，第三个部分
  private static final Function<String, String> retrieveNamespaceFromReleaseMessage =
      releaseMessage -> {
        if (Strings.isNullOrEmpty(releaseMessage)) {
          return null;
        }
        List<String> keys = STRING_SPLITTER.splitToList(releaseMessage);
        //message should be appId+cluster+namespace
        if (keys.size() != 3) {
          logger.error("message format invalid - {}", releaseMessage);
          return null;
        }
        return keys.get(2);
      };

  private void logWatchedKeys(Set<String> watchedKeys, String eventName) {
    for (String watchedKey : watchedKeys) {
      Tracer.logEvent(eventName, watchedKey);
    }
  }
}
