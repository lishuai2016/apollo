package com.ctrip.framework.apollo.openapi.util;

import com.ctrip.framework.apollo.openapi.service.ConsumerService;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

/**
 * @author Jason Song(song_s@ctrip.com)
 * 封装了ConsumerService
 */
@Service
public class ConsumerAuthUtil {
  static final String CONSUMER_ID = "ApolloConsumerId";//Request Attribute —— Consumer 编号
  private final ConsumerService consumerService;

  public ConsumerAuthUtil(final ConsumerService consumerService) {
    this.consumerService = consumerService;
  }

  public Long getConsumerId(String token) {//根据 Token 获得对应的 Consumer 编号
    return consumerService.getConsumerIdByToken(token);
  }
//设置 Consumer 编号到 Request
  public void storeConsumerId(HttpServletRequest request, Long consumerId) {
    request.setAttribute(CONSUMER_ID, consumerId);
  }
//获得 Consumer 编号从 Request
  public long retrieveConsumerId(HttpServletRequest request) {
    Object value = request.getAttribute(CONSUMER_ID);

    try {
      return Long.parseLong(value.toString());
    } catch (Throwable ex) {
      throw new IllegalStateException("No consumer id!", ex);
    }
  }
}
