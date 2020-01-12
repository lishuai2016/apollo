package com.ctrip.framework.apollo.common.config;

import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * refresh() 抽象方法，刷新配置。
 */
public abstract class RefreshablePropertySource extends MapPropertySource {


  public RefreshablePropertySource(String name, Map<String, Object> source) {
    super(name, source);
  }

  @Override
  public Object getProperty(String name) {
    return this.source.get(name);
  }

  /**
   * refresh property
   */
  protected abstract void refresh();

}
