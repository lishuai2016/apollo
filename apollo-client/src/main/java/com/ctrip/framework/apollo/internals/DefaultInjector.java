package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.spi.ConfigFactory;
import com.ctrip.framework.apollo.spi.ConfigFactoryManager;
import com.ctrip.framework.apollo.spi.ConfigRegistry;
import com.ctrip.framework.apollo.spi.DefaultConfigFactory;
import com.ctrip.framework.apollo.spi.DefaultConfigFactoryManager;
import com.ctrip.framework.apollo.spi.DefaultConfigRegistry;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.http.HttpUtil;

import com.ctrip.framework.apollo.util.yaml.YamlParser;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Singleton;

/**
 * Guice injector
 *
 * 依赖注入框架Google Guice
 *
 * @author Jason Song(song_s@ctrip.com)
 *
 *
 */
public class DefaultInjector implements Injector {
  private com.google.inject.Injector m_injector;

  public DefaultInjector() {
    try {
      m_injector = Guice.createInjector(new ApolloModule());
    } catch (Throwable ex) {
      ApolloConfigException exception = new ApolloConfigException("Unable to initialize Guice Injector!", ex);
      Tracer.logError(exception);
      throw exception;
    }
  }

  /**
   * 通过类的名称或者接口获得实例
   * @param clazz
   * @param <T>
   * @return
   */
  @Override
  public <T> T getInstance(Class<T> clazz) {
    try {
      return m_injector.getInstance(clazz);
    } catch (Throwable ex) {
      Tracer.logError(ex);
      throw new ApolloConfigException(
          String.format("Unable to load instance for %s!", clazz.getName()), ex);
    }
  }

  @Override
  public <T> T getInstance(Class<T> clazz, String name) {
    //Guice does not support get instance by type and name
    return null;
  }

  /**
   * 容器中的实例 使用 ApolloModule 类，告诉 Guice 需要 DI 的配置
   */
  private static class ApolloModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(ConfigManager.class).to(DefaultConfigManager.class).in(Singleton.class);
      bind(ConfigFactoryManager.class).to(DefaultConfigFactoryManager.class).in(Singleton.class);
      bind(ConfigRegistry.class).to(DefaultConfigRegistry.class).in(Singleton.class);
      bind(ConfigFactory.class).to(DefaultConfigFactory.class).in(Singleton.class);
      bind(ConfigUtil.class).in(Singleton.class);
      bind(HttpUtil.class).in(Singleton.class);
      bind(ConfigServiceLocator.class).in(Singleton.class);
      bind(RemoteConfigLongPollService.class).in(Singleton.class);
      bind(YamlParser.class).in(Singleton.class);
    }
  }
}
