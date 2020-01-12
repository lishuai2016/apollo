package com.ctrip.framework.apollo.openapi.util;

import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.openapi.entity.ConsumerAudit;
import com.ctrip.framework.apollo.openapi.service.ConsumerService;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@Service
public class ConsumerAuditUtil implements InitializingBean {
  private static final int CONSUMER_AUDIT_MAX_SIZE = 10000;
  private BlockingQueue<ConsumerAudit> audits = Queues.newLinkedBlockingQueue(CONSUMER_AUDIT_MAX_SIZE);//阻塞队列
  private final ExecutorService auditExecutorService;
  private final AtomicBoolean auditStopped;//是否停止
  private int BATCH_SIZE = 100;//批任务 ConsumerAudit 数量
  private long BATCH_TIMEOUT = 5;//批任务 ConsumerAudit 等待超时时间
  private TimeUnit BATCH_TIMEUNIT = TimeUnit.SECONDS;

  private final ConsumerService consumerService;

  public ConsumerAuditUtil(final ConsumerService consumerService) {
    this.consumerService = consumerService;
    //单线程池的
    auditExecutorService = Executors.newSingleThreadExecutor(
        ApolloThreadFactory.create("ConsumerAuditUtil", true));
    auditStopped = new AtomicBoolean(false);
  }

  public boolean audit(HttpServletRequest request, long consumerId) {
    //ignore GET request
    // 忽略 GET 请求
    if ("GET".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String uri = request.getRequestURI();
    if (!Strings.isNullOrEmpty(request.getQueryString())) {
      uri += "?" + request.getQueryString();
    }

    ConsumerAudit consumerAudit = new ConsumerAudit();
    Date now = new Date();
    consumerAudit.setConsumerId(consumerId);
    consumerAudit.setUri(uri);
    consumerAudit.setMethod(request.getMethod());
    consumerAudit.setDataChangeCreatedTime(now);
    consumerAudit.setDataChangeLastModifiedTime(now);

    //throw away audits if exceeds the max size
    return this.audits.offer(consumerAudit);
  }

  /**
   * 从阻塞队列中异步消费，然后保存到数据库
   * @throws Exception
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    auditExecutorService.submit(() -> {
      while (!auditStopped.get() && !Thread.currentThread().isInterrupted()) {
        List<ConsumerAudit> toAudit = Lists.newArrayList();
        try {
          // 获得 ConsumerAudit 批任务，直到到达上限，或者超时
          Queues.drain(audits, toAudit, BATCH_SIZE, BATCH_TIMEOUT, BATCH_TIMEUNIT);
          if (!toAudit.isEmpty()) {
            // 批量保存到数据库
            consumerService.createConsumerAudits(toAudit);
          }
        } catch (Throwable ex) {
          Tracer.logError(ex);
        }
      }
    });
  }

  public void stopAudit() {
    auditStopped.set(true);
  }
}
