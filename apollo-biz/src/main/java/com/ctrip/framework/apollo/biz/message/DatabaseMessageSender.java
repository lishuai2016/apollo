package com.ctrip.framework.apollo.biz.message;

import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.repository.ReleaseMessageRepository;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Jason Song(song_s@ctrip.com)
 * Admin Service在配置发布后会往ReleaseMessage表插入一条消息记录，
 * 消息内容就是配置发布的AppId+Cluster+Namespace，参见DatabaseMessageSender
 */
@Component
public class DatabaseMessageSender implements MessageSender {
  private static final Logger logger = LoggerFactory.getLogger(DatabaseMessageSender.class);
  private static final int CLEAN_QUEUE_MAX_SIZE = 100;//清理 Message 队列 最大容量
  private BlockingQueue<Long> toClean = Queues.newLinkedBlockingQueue(CLEAN_QUEUE_MAX_SIZE);//清理 Message 队列
  private final ExecutorService cleanExecutorService;
  private final AtomicBoolean cleanStopped;//是否停止清理 Message 标识

  private final ReleaseMessageRepository releaseMessageRepository;

  public DatabaseMessageSender(final ReleaseMessageRepository releaseMessageRepository) {
    cleanExecutorService = Executors.newSingleThreadExecutor(ApolloThreadFactory.create("DatabaseMessageSender", true));
    cleanStopped = new AtomicBoolean(false); // 设置 cleanStopped 为 false
    this.releaseMessageRepository = releaseMessageRepository;
  }

  /**
   * 保存ReleaseMessage消息到数据库
   * @param message
   * @param channel
   */
  @Override
  @Transactional
  public void sendMessage(String message, String channel) {
    logger.info("Sending message {} to channel {}", message, channel);
    if (!Objects.equals(channel, Topics.APOLLO_RELEASE_TOPIC)) {//只接受发布的消息
      logger.warn("Channel {} not supported by DatabaseMessageSender!");
      return;
    }

    Tracer.logEvent("Apollo.AdminService.ReleaseMessage", message);
    Transaction transaction = Tracer.newTransaction("Apollo.AdminService", "sendMessage");
    try {
      ReleaseMessage newMessage = releaseMessageRepository.save(new ReleaseMessage(message));
      // 添加到清理 Message 队列。若队列已满，添加失败，不阻塞等待。
      toClean.offer(newMessage.getId());
      transaction.setStatus(Transaction.SUCCESS);
    } catch (Throwable ex) {
      logger.error("Sending message to database failed", ex);
      transaction.setStatus(ex);
      throw ex;
    } finally {
      transaction.complete();
    }
  }

  @PostConstruct
  private void initialize() {
    cleanExecutorService.submit(() -> {
      // 若未停止，持续运行
      while (!cleanStopped.get() && !Thread.currentThread().isInterrupted()) {
        try {
          //拉取队头的消息编号
          Long rm = toClean.poll(1, TimeUnit.SECONDS);
          if (rm != null) {
            cleanMessage(rm);
          } else {//若未拉取到消息编号，说明队列为空，sleep ，避免空跑，占用 CPU
            TimeUnit.SECONDS.sleep(5);
          }
        } catch (Throwable ex) {
          Tracer.logError(ex);
        }
      }
    });
  }

  /**
   * 清理过期的消息
   * 什么 Config Service 和 Admin Service 都会启动清理任务呢？😈 因为 DatabaseMessageSender 添加了 @Component 注解，
   * 而 NamespaceService 注入了 DatabaseMessageSender 。
   * 而 NamespaceService 被 apollo-adminservice 和 apoll-configservice 项目都引用了，所以都会启动该任务
   * @param id
   */
  private void cleanMessage(Long id) {
    boolean hasMore = true;
    //double check in case the release message is rolled back
    /**
     * // 查询对应的 ReleaseMessage 对象，避免已经删除。因为，DatabaseMessageSender 会在多进程中执行。
     * 例如：1）Config Service + Admin Service ；
     * 2）N * Config Service ；
     * 3）N * Admin Service
     */
    ReleaseMessage releaseMessage = releaseMessageRepository.findById(id).orElse(null);
    if (releaseMessage == null) {
      return;
    }
    // 循环删除相同消息内容( `message` )的老消息
    while (hasMore && !Thread.currentThread().isInterrupted()) {
      // 拉取相同消息内容的 100 条的老消息,老消息的定义：比当前消息编号小，即先发送的,按照 id 升序
      List<ReleaseMessage> messages = releaseMessageRepository.findFirst100ByMessageAndIdLessThanOrderByIdAsc(
          releaseMessage.getMessage(), releaseMessage.getId());
      // 删除老消息
      releaseMessageRepository.deleteAll(messages);
      // 若拉取不足 100 条，说明无老消息了
      hasMore = messages.size() == 100;

      messages.forEach(toRemove -> Tracer.logEvent(
          String.format("ReleaseMessage.Clean.%s", toRemove.getMessage()), String.valueOf(toRemove.getId())));
    }
  }

  void stopClean() {
    cleanStopped.set(true);
  }
}
