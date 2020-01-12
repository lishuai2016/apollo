package com.ctrip.framework.apollo.portal.listener;

import com.google.common.base.Preconditions;

import com.ctrip.framework.apollo.common.entity.AppNamespace;

import org.springframework.context.ApplicationEvent;

/**
 AppNamespace 创建事件
 */

public class AppNamespaceCreationEvent extends ApplicationEvent {

  public AppNamespaceCreationEvent(Object source) {
    super(source);
  }

  public AppNamespace getAppNamespace() {
    Preconditions.checkState(source != null);
    return (AppNamespace) this.source;
  }
}
