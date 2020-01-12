package com.ctrip.framework.apollo.configservice.util;

import com.ctrip.framework.apollo.common.entity.AppNamespace;
import com.ctrip.framework.apollo.configservice.service.AppNamespaceServiceWithCache;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Jason Song(song_s@ctrip.com)
 *
 * Watch Key 工具类。

核心的方法为 #assembleAllWatchKeys(appId, clusterName, namespaces, dataCenter) 方法，组装 Watch Key Multimap 。
其中 KEY 为 Namespace 的名字，VALUE 为 Watch Key 集合
依据namespace为key,value为：appId+cluster+namespace构建map
 */
@Component
public class WatchKeysUtil {
  private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR);
  private final AppNamespaceServiceWithCache appNamespaceService;

  public WatchKeysUtil(final AppNamespaceServiceWithCache appNamespaceService) {
    this.appNamespaceService = appNamespaceService;
  }

  /**
   * Assemble watch keys for the given appId, cluster, namespace, dataCenter combination
   */
  public Set<String> assembleAllWatchKeys(String appId, String clusterName, String namespace,
                                          String dataCenter) {
    Multimap<String, String> watchedKeysMap =
        assembleAllWatchKeys(appId, clusterName, Sets.newHashSet(namespace), dataCenter);
    return Sets.newHashSet(watchedKeysMap.get(namespace));
  }

  /**
   * Assemble watch keys for the given appId, cluster, namespaces, dataCenter combination
   *  收集和组装watch key
   *
   *  依据namespace为key,value为：appId+cluster+namespace构建map
   * @return a multimap with namespace as the key and watch keys as the value
   */
  public Multimap<String, String> assembleAllWatchKeys(String appId, String clusterName,
                                                       Set<String> namespaces,
                                                       String dataCenter) {
    Multimap<String, String> watchedKeysMap =
        assembleWatchKeys(appId, clusterName, namespaces, dataCenter);//依据namespace为key,value为：appId+cluster+namespace构建map

    /**
     * 判断 namespaces 中，可能存在关联类型的 Namespace ，因此需要进一步处理。在这里的判断会比较“绕”，
     * 如果 namespaces 仅仅是 "application" 时，那么肯定不存在关联类型的 Namespace
     */
    //Every app has an 'application' namespace
    if (!(namespaces.size() == 1 && namespaces.contains(ConfigConsts.NAMESPACE_APPLICATION))) {
      //获得属于该 App 的 Namespace 的名字的集合
      Set<String> namespacesBelongToAppId = namespacesBelongToAppId(appId, namespaces);
      //进行集合差异计算，获得关联类型的 Namespace 的名字的集合
      Set<String> publicNamespaces = Sets.difference(namespaces, namespacesBelongToAppId);

      //Listen on more namespaces if it's a public namespace
      //获得关联类型的 Namespace 的名字的集合的 Watch Key Multimap ，并添加到结果集中
      if (!publicNamespaces.isEmpty()) {
        watchedKeysMap
            .putAll(findPublicConfigWatchKeys(appId, clusterName, publicNamespaces, dataCenter));
      }
    }

    return watchedKeysMap;
  }

  /**
   * 获得 Namespace 类型为 public 对应的 Watch Key Multimap
   * @param applicationId
   * @param clusterName
   * @param namespaces
   * @param dataCenter
   * @return
   */
  private Multimap<String, String> findPublicConfigWatchKeys(String applicationId,
                                                             String clusterName,
                                                             Set<String> namespaces,
                                                             String dataCenter) {
    Multimap<String, String> watchedKeysMap = HashMultimap.create();
    // 获得 Namespace 为 public 的 AppNamespace 数组
    List<AppNamespace> appNamespaces = appNamespaceService.findPublicNamespacesByNames(namespaces);

    for (AppNamespace appNamespace : appNamespaces) {
      // 排除非关联类型的 Namespace
      //check whether the namespace's appId equals to current one
      if (Objects.equals(applicationId, appNamespace.getAppId())) {
        continue;
      }

      String publicConfigAppId = appNamespace.getAppId();
    // 组装指定 Namespace 的 Watch Key 数组
      watchedKeysMap.putAll(appNamespace.getName(),
          assembleWatchKeys(publicConfigAppId, clusterName, appNamespace.getName(), dataCenter));
    }

    return watchedKeysMap;
  }

  //一个key的拼接组成：appId+cluster+namespace，唯一标识？？？
  private String assembleKey(String appId, String cluster, String namespace) {
    return STRING_JOINER.join(appId, cluster, namespace);
  }

  //拼接watchkey
  private Set<String> assembleWatchKeys(String appId, String clusterName, String namespace,
                                        String dataCenter) {
    if (ConfigConsts.NO_APPID_PLACEHOLDER.equalsIgnoreCase(appId)) {
      return Collections.emptySet();
    }
    Set<String> watchedKeys = Sets.newHashSet();

    //watch specified cluster config change
    if (!Objects.equals(ConfigConsts.CLUSTER_NAME_DEFAULT, clusterName)) {
      watchedKeys.add(assembleKey(appId, clusterName, namespace));
    }

    //watch data center config change
    if (!Strings.isNullOrEmpty(dataCenter) && !Objects.equals(dataCenter, clusterName)) {
      watchedKeys.add(assembleKey(appId, dataCenter, namespace));
    }

    //watch default cluster config change  默认添加的
    watchedKeys.add(assembleKey(appId, ConfigConsts.CLUSTER_NAME_DEFAULT, namespace));

    return watchedKeys;
  }

  private Multimap<String, String> assembleWatchKeys(String appId, String clusterName,
                                                     Set<String> namespaces,
                                                     String dataCenter) {
    Multimap<String, String> watchedKeysMap = HashMultimap.create();

    for (String namespace : namespaces) {
      watchedKeysMap
          .putAll(namespace, assembleWatchKeys(appId, clusterName, namespace, dataCenter));
    }

    return watchedKeysMap;
  }

  /**
   * 获得这个appid关联上的namespace，从appNamespaceCache上面获取
   * @param appId
   * @param namespaces
   * @return
   */
  private Set<String> namespacesBelongToAppId(String appId, Set<String> namespaces) {
    if (ConfigConsts.NO_APPID_PLACEHOLDER.equalsIgnoreCase(appId)) {
      return Collections.emptySet();
    }
    List<AppNamespace> appNamespaces =
        appNamespaceService.findByAppIdAndNamespaces(appId, namespaces);

    if (appNamespaces == null || appNamespaces.isEmpty()) {
      return Collections.emptySet();
    }

    return appNamespaces.stream().map(AppNamespace::getName).collect(Collectors.toSet());
  }
}
