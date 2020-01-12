package com.ctrip.framework.apollo.core;

import com.ctrip.framework.apollo.core.enums.Env;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.framework.apollo.core.spi.MetaServerProvider;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.core.utils.NetUtil;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.ctrip.framework.foundation.internals.ServiceBootstrap;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 *
 * 这个类会尝试从MetaServerProviders加载meta server的地址，顺序是：
 * LegacyMetaServerProvider
 * 如果获取不到使用http://apollo.meta作为默认的地址，用户可以基于spi机制自己定时MetaServerProvider的实现类
 *
 * The meta domain will try to load the meta server address from MetaServerProviders, the default ones are:
 *
 * <ul>
 * <li>com.ctrip.framework.apollo.core.internals.LegacyMetaServerProvider</li>
 * </ul>
 *
 * If no provider could provide the meta server url, the default meta url will be used(http://apollo.meta).
 * <br />
 *
 * 3rd party MetaServerProvider could be injected by typical Java Service Loader pattern.
 *
 * @see com.ctrip.framework.apollo.core.internals.LegacyMetaServerProvider
 *
 *
 * Meta Service 多环境的地址枚举类
 *
 */
public class MetaDomainConsts {
  public static final String DEFAULT_META_URL = "http://apollo.meta";

  // env -> meta server address cache  不同的环境缓存的meta service地址
  private static final Map<Env, String> metaServerAddressCache = Maps.newConcurrentMap();
  private static volatile List<MetaServerProvider> metaServerProviders = null;

  private static final long REFRESH_INTERVAL_IN_SECOND = 60;// 1 min
  private static final Logger logger = LoggerFactory.getLogger(MetaDomainConsts.class);
  // comma separated meta server address -> selected single meta server address cache. key是整个地址串  其实value只是保存了一个地址
  private static final Map<String, String> selectedMetaServerAddressCache = Maps.newConcurrentMap();
  private static final AtomicBoolean periodicRefreshStarted = new AtomicBoolean(false);

  private static final Object LOCK = new Object();

  /**
   * 这个方法是该类外部访问的入口
   *
   * 根据环境获得meta server address地址，如果多个选择一个返回
   * Return one meta server address. If multiple meta server addresses are configured, will select one.
   */
  public static String getDomain(Env env) {
    String metaServerAddress = getMetaServerAddress(env);   //这里拿到的可能包含多个用逗号分隔的地址串
    // if there is more than one address, need to select one
    if (metaServerAddress.contains(",")) { //对多个地址串进行随机算法，获得其中一个
      return selectMetaServerAddress(metaServerAddress);
    }
    return metaServerAddress;
  }

  /**
   * Return meta server address. If multiple meta server addresses are configured, will return the comma separated string.
    返回的内容可能包含逗号分隔的多个地址
   */
  public static String getMetaServerAddress(Env env) {
    if (!metaServerAddressCache.containsKey(env)) {
      initMetaServerAddress(env);
    }

    return metaServerAddressCache.get(env);
  }

  /**
   * 首先根据spi机制获得接口实现类列表，然后遍历列表获得metaAddress地址，并且把根据环境的类型来缓存不同的metaAddress
   * 放入缓存metaServerAddressCache中
   *
   * @param env
   */
  private static void initMetaServerAddress(Env env) {
    if (metaServerProviders == null) {
      synchronized (LOCK) {
        if (metaServerProviders == null) {
          metaServerProviders = initMetaServerProviders();
        }
      }
    }

    String metaAddress = null;

    for (MetaServerProvider provider : metaServerProviders) {//遍历获得metaAddress
      metaAddress = provider.getMetaServerAddress(env);
      if (!Strings.isNullOrEmpty(metaAddress)) {
        logger.info("Located meta server address {} for env {} from {}", metaAddress, env,
            provider.getClass().getName());
        break;
      }
    }

    if (Strings.isNullOrEmpty(metaAddress)) {
      // Fallback to default meta address
      metaAddress = DEFAULT_META_URL;
      logger.warn(
          "Meta server address fallback to {} for env {}, because it is not available in all MetaServerProviders",
          metaAddress, env);
    }

    metaServerAddressCache.put(env, metaAddress.trim());//缓存到本地map
  }

  /**
   * 返回spi机制下接口MetaServerProvider的全部实现类，并按照Oder的顺序排序，返回list对象
   * @return
   */
  private static List<MetaServerProvider> initMetaServerProviders() {
    Iterator<MetaServerProvider> metaServerProviderIterator = ServiceBootstrap.loadAll(MetaServerProvider.class);

    List<MetaServerProvider> metaServerProviders = Lists.newArrayList(metaServerProviderIterator);

    Collections.sort(metaServerProviders, new Comparator<MetaServerProvider>() {
      @Override
      public int compare(MetaServerProvider o1, MetaServerProvider o2) {
        // the smaller order has higher priority  order的顺序越小优先级越高
        return Integer.compare(o1.getOrder(), o2.getOrder());
      }
    });

    return metaServerProviders;
  }

  /**
   * 从多个逗号分隔的meta server地址中获得一个返回
   *
   * Select one available meta server from the comma separated meta server addresses, e.g.
   * http://1.2.3.4:8080,http://2.3.4.5:8080
   *
   * <br />
   *
   * In production environment, we still suggest using one single domain like http://config.xxx.com(backed by software
   * load balancers like nginx) instead of multiple ip addresses
   */
  private static String selectMetaServerAddress(String metaServerAddresses) {
    String metaAddressSelected = selectedMetaServerAddressCache.get(metaServerAddresses);
    if (metaAddressSelected == null) {
      // initialize
      if (periodicRefreshStarted.compareAndSet(false, true)) {
        schedulePeriodicRefresh();
      }
      updateMetaServerAddresses(metaServerAddresses);
      metaAddressSelected = selectedMetaServerAddressCache.get(metaServerAddresses);
    }

    return metaAddressSelected;
  }

  //更新selectedMetaServerAddressCache缓存的meta server地址
  private static void updateMetaServerAddresses(String metaServerAddresses) {
    logger.debug("Selecting meta server address for: {}", metaServerAddresses);

    Transaction transaction = Tracer.newTransaction("Apollo.MetaService", "refreshMetaServerAddress");
    transaction.addData("Url", metaServerAddresses);

    try {
      List<String> metaServers = Lists.newArrayList(metaServerAddresses.split(","));
      // random load balancing
      Collections.shuffle(metaServers);

      boolean serverAvailable = false;

      for (String address : metaServers) {
        address = address.trim();
        //check whether /services/config is accessible  测试服务是否可用
        if (NetUtil.pingUrl(address + "/services/config")) {
          // select the first available meta server
          selectedMetaServerAddressCache.put(metaServerAddresses, address);
          serverAvailable = true;
          logger.debug("Selected meta server address {} for {}", address, metaServerAddresses);
          break;
        }
      }

      // we need to make sure the map is not empty, e.g. the first update might be failed
      if (!selectedMetaServerAddressCache.containsKey(metaServerAddresses)) {
        selectedMetaServerAddressCache.put(metaServerAddresses, metaServers.get(0).trim());
      }

      if (!serverAvailable) {
        logger.warn("Could not find available meta server for configured meta server addresses: {}, fallback to: {}",
            metaServerAddresses, selectedMetaServerAddressCache.get(metaServerAddresses));
      }

      transaction.setStatus(Transaction.SUCCESS);
    } catch (Throwable ex) {
      transaction.setStatus(ex);
      throw ex;
    } finally {
      transaction.complete();
    }
  }

  //定时刷新可用的meta server地址
  private static void schedulePeriodicRefresh() {
    ScheduledExecutorService scheduledExecutorService =
        Executors.newScheduledThreadPool(1, ApolloThreadFactory.create("MetaServiceLocator", true));

    scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        try {
          for (String metaServerAddresses : selectedMetaServerAddressCache.keySet()) {
            updateMetaServerAddresses(metaServerAddresses);
          }
        } catch (Throwable ex) {
          logger.warn(String.format("Refreshing meta server address failed, will retry in %d seconds",
              REFRESH_INTERVAL_IN_SECOND), ex);
        }
      }
    }, REFRESH_INTERVAL_IN_SECOND, REFRESH_INTERVAL_IN_SECOND, TimeUnit.SECONDS);
  }
}
