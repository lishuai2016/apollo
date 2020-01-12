package com.ctrip.framework.apollo.biz.service;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;

import com.ctrip.framework.apollo.biz.entity.ServerConfig;
import com.ctrip.framework.apollo.biz.repository.ServerConfigRepository;
import com.ctrip.framework.apollo.common.config.RefreshablePropertySource;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.foundation.Foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * @author Jason Song(song_s@ctrip.com)
 * 把DBConfig中的ServerConfig的配置数据更新到内存中
 *
 * 提供给 Config Service、Admin Service 服务使用。
相比 PortalDBPropertySource ，BizDBPropertySource 多了多机房部署的 Cluster 过滤。
在 #refresh() 实现方法中，按照默认 的 Cluster、数据中心的 Cluster、JVM 启动参数的 Cluster ，
逐个匹配 ServerConfig 的 cluster 字段。若匹配，最终会更新到属性源。
 *
 */
@Component
public class BizDBPropertySource extends RefreshablePropertySource {

  private static final Logger logger = LoggerFactory.getLogger(BizDBPropertySource.class);

  @Autowired
  private ServerConfigRepository serverConfigRepository;

  public BizDBPropertySource(String name, Map<String, Object> source) {
    super(name, source);
  }

  public BizDBPropertySource() {
    super("DBConfig", Maps.newConcurrentMap());
  }

  String getCurrentDataCenter() {
    return Foundation.server().getDataCenter();
  }

  @Override
  protected void refresh() {
    Iterable<ServerConfig> dbConfigs = serverConfigRepository.findAll();

    // 创建配置 Map ，将匹配的 Cluster 的 ServerConfig 添加到其中
    Map<String, Object> newConfigs = Maps.newHashMap();
    //default cluster's configs
    for (ServerConfig config : dbConfigs) {
      if (Objects.equals(ConfigConsts.CLUSTER_NAME_DEFAULT, config.getCluster())) {
        newConfigs.put(config.getKey(), config.getValue());
      }
    }

    //data center's configs
    String dataCenter = getCurrentDataCenter();
    for (ServerConfig config : dbConfigs) {
      if (Objects.equals(dataCenter, config.getCluster())) {
        newConfigs.put(config.getKey(), config.getValue());
      }
    }

    //cluster's config
    if (!Strings.isNullOrEmpty(System.getProperty(ConfigConsts.APOLLO_CLUSTER_KEY))) {
      String cluster = System.getProperty(ConfigConsts.APOLLO_CLUSTER_KEY);
      for (ServerConfig config : dbConfigs) {
        if (Objects.equals(cluster, config.getCluster())) {
          newConfigs.put(config.getKey(), config.getValue());
        }
      }
    }

// 缓存，更新到属性源
    //put to environment
    for (Map.Entry<String, Object> config: newConfigs.entrySet()){
      String key = config.getKey();
      Object value = config.getValue();

      if (this.source.get(key) == null) {
        logger.info("Load config from DB : {} = {}", key, value);
      } else if (!Objects.equals(this.source.get(key), value)) {
        logger.info("Load config from DB : {} = {}. Old value = {}", key,
                    value, this.source.get(key));
      }

      this.source.put(key, value);

    }

  }

}
