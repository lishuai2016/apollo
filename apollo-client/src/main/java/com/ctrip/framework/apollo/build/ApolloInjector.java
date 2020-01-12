package com.ctrip.framework.apollo.build;

import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.internals.Injector;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.foundation.internals.ServiceBootstrap;

/**
 * @author Jason Song(song_s@ctrip.com)
 * Apollo 注入器，实现依赖注入( DI，全称“Dependency Injection” )
 */
public class ApolloInjector {
  private static volatile Injector s_injector;//注入器
  private static final Object lock = new Object();//锁

  /**
   * 根据jdk的spi机制，获得Injector接口的第一个实现类
   * 双层检测获得实例
   * @return
   */
  private static Injector getInjector() {
    // 若 Injector 不存在，则进行获得
    if (s_injector == null) {
      synchronized (lock) {
        if (s_injector == null) {
          try {
            // 基于 JDK SPI 加载对应的 Injector 实现对象
            s_injector = ServiceBootstrap.loadFirst(Injector.class);
          } catch (Throwable ex) {
            ApolloConfigException exception = new ApolloConfigException("Unable to initialize Apollo Injector!", ex);
            Tracer.logError(exception);
            throw exception;
          }
        }
      }
    }

    return s_injector;
  }

  public static <T> T getInstance(Class<T> clazz) {
    try {
      return getInjector().getInstance(clazz);
    } catch (Throwable ex) {
      Tracer.logError(ex);
      throw new ApolloConfigException(String.format("Unable to load instance for type %s!", clazz.getName()), ex);
    }
  }

  public static <T> T getInstance(Class<T> clazz, String name) {
    try {
      return getInjector().getInstance(clazz, name);
    } catch (Throwable ex) {
      Tracer.logError(ex);
      throw new ApolloConfigException(
          String.format("Unable to load instance for type %s and name %s !", clazz.getName(), name), ex);
    }
  }
}
