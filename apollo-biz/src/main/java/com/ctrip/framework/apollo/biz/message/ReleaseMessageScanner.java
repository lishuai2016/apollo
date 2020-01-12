package com.ctrip.framework.apollo.biz.message;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.repository.ReleaseMessageRepository;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.google.common.collect.Lists;

/**
 * @author Jason Song(song_s@ctrip.com)
 * Config Service有一个线程会每秒扫描一次ReleaseMessage表，看看是否有新的消息记录，参见ReleaseMessageScanner
 */
public class ReleaseMessageScanner implements InitializingBean {
  private static final Logger logger = LoggerFactory.getLogger(ReleaseMessageScanner.class);
  @Autowired
  private BizConfig bizConfig;
  @Autowired
  private ReleaseMessageRepository releaseMessageRepository;
  private int databaseScanInterval;//从 DB 中扫描 ReleaseMessage 表的频率，单位：毫秒.默认：1000 ms
  private List<ReleaseMessageListener> listeners;//监听器数组，在 MessageScannerConfiguration 中，调用该方法，初始化 ReleaseMessageScanner 的监听器们
  private ScheduledExecutorService executorService;
  private long maxIdScanned;

  public ReleaseMessageScanner() {
    listeners = Lists.newCopyOnWriteArrayList();
    executorService = Executors.newScheduledThreadPool(1, ApolloThreadFactory
        .create("ReleaseMessageScanner", true));
  }

  /**
   * 初始化 Scan 任务
   * @throws Exception
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    // 从 ServerConfig 中获得频率
    databaseScanInterval = bizConfig.releaseMessageScanIntervalInMilli();
    // 获得最大的 ReleaseMessage 的编号
    maxIdScanned = loadLargestMessageId();
    // 创建从 DB 中扫描 ReleaseMessage 表的定时任务
    executorService.scheduleWithFixedDelay((Runnable) () -> {
      Transaction transaction = Tracer.newTransaction("Apollo.ReleaseMessageScanner", "scanMessage");
      try {
        // 从 DB 中，扫描 ReleaseMessage 们
        scanMessages();
        transaction.setStatus(Transaction.SUCCESS);
      } catch (Throwable ex) {
        transaction.setStatus(ex);
        logger.error("Scan and send message failed", ex);
      } finally {
        transaction.complete();
      }
    }, databaseScanInterval, databaseScanInterval, TimeUnit.MILLISECONDS);

  }

  /**
   * add message listeners for release message
   *
   * 添加监听器到数组中
   * @param listener
   */
  public void addMessageListener(ReleaseMessageListener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  /**
   * Scan messages, continue scanning until there is no more messages
   */
  private void scanMessages() {
    boolean hasMoreMessages = true;
    while (hasMoreMessages && !Thread.currentThread().isInterrupted()) {
      hasMoreMessages = scanAndSendMessages();
    }
  }

  /**
   * scan messages and send
   * 获得大于 maxIdScanned 的 500 条 ReleaseMessage 记录，按照 id 升序
   * @return whether there are more messages
   */
  private boolean scanAndSendMessages() {
    //current batch is 500
    List<ReleaseMessage> releaseMessages =
        releaseMessageRepository.findFirst500ByIdGreaterThanOrderByIdAsc(maxIdScanned);
    if (CollectionUtils.isEmpty(releaseMessages)) {
      return false;
    }
    fireMessageScanned(releaseMessages);//触发监听器们
    int messageScanned = releaseMessages.size();
    maxIdScanned = releaseMessages.get(messageScanned - 1).getId();
    return messageScanned == 500;
  }

  /**
   * find largest message id as the current start point
   * @return current largest message id
   */
  private long loadLargestMessageId() {
    ReleaseMessage releaseMessage = releaseMessageRepository.findTopByOrderByIdDesc();
    return releaseMessage == null ? 0 : releaseMessage.getId();
  }

  /**
   * Notify listeners with messages loaded
   * 触发监听器发布消息
   * @param messages
   */
  private void fireMessageScanned(List<ReleaseMessage> messages) {
    for (ReleaseMessage message : messages) {
      for (ReleaseMessageListener listener : listeners) {
        try {
          listener.handleMessage(message, Topics.APOLLO_RELEASE_TOPIC);
        } catch (Throwable ex) {
          Tracer.logError(ex);
          logger.error("Failed to invoke message listener {}", listener.getClass(), ex);
        }
      }
    }
  }
}
