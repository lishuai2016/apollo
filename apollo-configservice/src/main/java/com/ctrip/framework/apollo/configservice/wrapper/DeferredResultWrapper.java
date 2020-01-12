package com.ctrip.framework.apollo.configservice.wrapper;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import com.ctrip.framework.apollo.core.dto.ApolloConfigNotification;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;

/**
 * @author Jason Song(song_s@ctrip.com)
 *
 * 这里是对spring的DeferredResult进行的包装类
 */
public class DeferredResultWrapper {
  private static final long TIMEOUT = 60 * 1000;//60 seconds
  private static final ResponseEntity<List<ApolloConfigNotification>>
      NOT_MODIFIED_RESPONSE_LIST = new ResponseEntity<>(HttpStatus.NOT_MODIFIED);//没有变化的返回值

  private Map<String, String> normalizedNamespaceNameToOriginalNamespaceName;//归一化后的namespace和原始的namespace直接的映射
  private DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> result;


  public DeferredResultWrapper() {
    result = new DeferredResult<>(TIMEOUT, NOT_MODIFIED_RESPONSE_LIST);//初始化spring的DeferredResult，并指定超时时间已经返回的内容
  }

  /**
   * 归一化后的名字和原始的名字的映射
   * @param originalNamespaceName
   * @param normalizedNamespaceName
   */
  public void recordNamespaceNameNormalizedResult(String originalNamespaceName, String normalizedNamespaceName) {
    if (normalizedNamespaceNameToOriginalNamespaceName == null) {
      normalizedNamespaceNameToOriginalNamespaceName = Maps.newHashMap();
    }
    normalizedNamespaceNameToOriginalNamespaceName.put(normalizedNamespaceName, originalNamespaceName);
  }

  //处理超时
  public void onTimeout(Runnable timeoutCallback) {
    result.onTimeout(timeoutCallback);
  }
  //处理完成后的操作
  public void onCompletion(Runnable completionCallback) {
    result.onCompletion(completionCallback);
  }

  //设置结果返回
  public void setResult(ApolloConfigNotification notification) {
    setResult(Lists.newArrayList(notification));
  }

  /**
   * The namespace name is used as a key in client side, so we have to return the original one instead of the correct one
   * 把归一化后的namespace映射会原始的namespace
   */
  public void setResult(List<ApolloConfigNotification> notifications) {
    if (normalizedNamespaceNameToOriginalNamespaceName != null) {
      notifications.stream().filter(notification -> normalizedNamespaceNameToOriginalNamespaceName.containsKey
          (notification.getNamespaceName())).forEach(notification -> notification.setNamespaceName(
              normalizedNamespaceNameToOriginalNamespaceName.get(notification.getNamespaceName())));
    }

    result.setResult(new ResponseEntity<>(notifications, HttpStatus.OK));
  }

  public DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> getResult() {
    return result;
  }
}
