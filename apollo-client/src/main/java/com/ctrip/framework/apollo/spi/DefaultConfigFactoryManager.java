package com.ctrip.framework.apollo.spi;

import java.util.Map;

import com.ctrip.framework.apollo.build.ApolloInjector;
import com.google.common.collect.Maps;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
public class DefaultConfigFactoryManager implements ConfigFactoryManager {
  private ConfigRegistry m_registry;

  private Map<String, ConfigFactory> m_factories = Maps.newConcurrentMap();

  public DefaultConfigFactoryManager() {
    m_registry = ApolloInjector.getInstance(ConfigRegistry.class);//实例化注册管理实例
  }

  /**
   * 根据namespace获得一个ConfigFactory实例
   *
   * 大多数情况下，使用 step 2 和 step 4 从 ApolloInjector 中，获得默认的 ConfigFactory 对象
   *
   * @param namespace the namespace
   * @return
   */
  @Override
  public ConfigFactory getFactory(String namespace) {
    // step 1: check hacked factory  如果已经注册过直接返回
    ConfigFactory factory = m_registry.getFactory(namespace);

    if (factory != null) {
      return factory;
    }

    // step 2: check cache  检测自己的缓存中是否有，有的话直接返回
    factory = m_factories.get(namespace);

    if (factory != null) {
      return factory;
    }

    // step 3: check declared config factory  现在没有实现返回一直都是null
    factory = ApolloInjector.getInstance(ConfigFactory.class, namespace);

    if (factory != null) {
      return factory;
    }

    // step 4: check default config factory
    factory = ApolloInjector.getInstance(ConfigFactory.class);

    m_factories.put(namespace, factory); //放到自己本地的缓存中

    // factory should not be null
    return factory;
  }
}
