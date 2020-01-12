package com.ctrip.framework.apollo.common.config;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;


/**
 * 可刷新的配置抽象类,两个实现类：
 * 1、BizConfig
 * 2、PortalConfig
 * 来实现两个数据库表配置的定时刷新
 */

public abstract class RefreshableConfig {

  private static final Logger logger = LoggerFactory.getLogger(RefreshableConfig.class);

  private static final String LIST_SEPARATOR = ",";
  //TimeUnit: second
  private static final int CONFIG_REFRESH_INTERVAL = 60;//60秒刷新一次

  protected Splitter splitter = Splitter.on(LIST_SEPARATOR).omitEmptyStrings().trimResults();

  /**
   * environment 属性，Spring ConfigurableEnvironment 对象。
   * 其 PropertySource 不仅仅包括 propertySources ，还包括 yaml properties 等 PropertySource 。
   * 这就是为什么 ServerConfig 被封装成 PropertySource 的原因。
   */
  @Autowired
  private ConfigurableEnvironment environment;//这个的作用？？？

  //需要更新的对象
  private List<RefreshablePropertySource> propertySources;

  /**
   * register refreshable property source.
   * Notice: The front property source has higher priority.
   */
  protected abstract List<RefreshablePropertySource> getRefreshablePropertySources();

  /**
   * 通过 Spring 调用，初始化定时刷新配置任务
   */
  @PostConstruct
  public void setup() {

    propertySources = getRefreshablePropertySources();//留给子类去实现，然后传入
    if (CollectionUtils.isEmpty(propertySources)) {
      throw new IllegalStateException("Property sources can not be empty.");
    }

    //add property source to environment
    for (RefreshablePropertySource propertySource : propertySources) {
      propertySource.refresh();
      environment.getPropertySources().addLast(propertySource);
    }

    //task to update configs
    ScheduledExecutorService
        executorService =
        Executors.newScheduledThreadPool(1, ApolloThreadFactory.create("ConfigRefresher", true));

    executorService
        .scheduleWithFixedDelay(() -> {
          try {
            propertySources.forEach(RefreshablePropertySource::refresh);//执行定时刷新动作，把数据库中的内容更新到内存中
          } catch (Throwable t) {
            logger.error("Refresh configs failed.", t);
            Tracer.logError("Refresh configs failed.", t);
          }
        }, CONFIG_REFRESH_INTERVAL, CONFIG_REFRESH_INTERVAL, TimeUnit.SECONDS);
  }
  //每个方法中，调用 ConfigurableEnvironment#getProperty(key, defaultValue) 方法，进行转换后返回值
  public int getIntProperty(String key, int defaultValue) {
    try {
      String value = getValue(key);
      return value == null ? defaultValue : Integer.parseInt(value);
    } catch (Throwable e) {
      Tracer.logError("Get int property failed.", e);
      return defaultValue;
    }
  }

  public boolean getBooleanProperty(String key, boolean defaultValue) {
    try {
      String value = getValue(key);
      return value == null ? defaultValue : "true".equals(value);
    } catch (Throwable e) {
      Tracer.logError("Get boolean property failed.", e);
      return defaultValue;
    }
  }

  public String[] getArrayProperty(String key, String[] defaultValue) {
    try {
      String value = getValue(key);
      return Strings.isNullOrEmpty(value) ? defaultValue : value.split(LIST_SEPARATOR);
    } catch (Throwable e) {
      Tracer.logError("Get array property failed.", e);
      return defaultValue;
    }
  }

  public String getValue(String key, String defaultValue) {
    try {
      return environment.getProperty(key, defaultValue);
    } catch (Throwable e) {
      Tracer.logError("Get value failed.", e);
      return defaultValue;
    }
  }

  public String getValue(String key) {
    return environment.getProperty(key);
  }

}
