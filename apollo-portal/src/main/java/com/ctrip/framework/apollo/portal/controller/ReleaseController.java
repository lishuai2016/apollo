package com.ctrip.framework.apollo.portal.controller;

import com.ctrip.framework.apollo.common.dto.ReleaseDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.exception.NotFoundException;
import com.ctrip.framework.apollo.common.utils.RequestPrecondition;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.portal.component.PermissionValidator;
import com.ctrip.framework.apollo.portal.component.config.PortalConfig;
import com.ctrip.framework.apollo.portal.entity.bo.ReleaseBO;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceReleaseModel;
import com.ctrip.framework.apollo.portal.entity.vo.ReleaseCompareResult;
import com.ctrip.framework.apollo.portal.listener.ConfigPublishEvent;
import com.ctrip.framework.apollo.portal.service.ReleaseService;
import javax.validation.Valid;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.ctrip.framework.apollo.common.utils.RequestPrecondition.checkModel;

/**

 在配置中心中，一个重要的功能就是配置发布后实时推送到客户端。下面我们简要看一下这块是怎么设计实现的。

 1、用户在 Portal 操作配置发布
 2、Portal 调用 Admin Service 的接口操作发布【通过http的方式】
 3、Admin Service 发布配置后，发送 ReleaseMessage 给各个Config Service【插入数据库 Release、ReleaseHistory、ReleaseMessage】
 4、Config Service 收到 ReleaseMessage 后，通知对应的客户端

 */


@Validated
@RestController
public class ReleaseController {

  private final ReleaseService releaseService;
  private final ApplicationEventPublisher publisher;
  private final PortalConfig portalConfig;
  private final PermissionValidator permissionValidator;

  public ReleaseController(
      final ReleaseService releaseService,
      final ApplicationEventPublisher publisher,
      final PortalConfig portalConfig,
      final PermissionValidator permissionValidator) {
    this.releaseService = releaseService;
    this.publisher = publisher;
    this.portalConfig = portalConfig;
    this.permissionValidator = permissionValidator;
  }

  /**
   * 点击发布按钮触发的接口
   *
   *
   * @param appId
   * @param env
   * @param clusterName
   * @param namespaceName
   * @param model
   * @return
   */
  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName, #env)")
  @PostMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/releases")
  public ReleaseDTO createRelease(@PathVariable String appId,
                                  @PathVariable String env, @PathVariable String clusterName,
                                  @PathVariable String namespaceName, @RequestBody NamespaceReleaseModel model) {
    // 设置 PathVariable 变量到 NamespaceReleaseModel 中
    model.setAppId(appId);
    model.setEnv(env);
    model.setClusterName(clusterName);
    model.setNamespaceName(namespaceName);

    // 若是紧急发布，但是当前环境未允许该操作，抛出 BadRequestException 异常
    if (model.isEmergencyPublish() && !portalConfig.isEmergencyPublishAllowed(Env.valueOf(env))) {
      throw new BadRequestException(String.format("Env: %s is not supported emergency publish now", env));
    }

    // 发布配置【重点】 通过http方式调用admin模块的接口
    ReleaseDTO createdRelease = releaseService.publish(model);

    // 创建 ConfigPublishEvent 对象
    ConfigPublishEvent event = ConfigPublishEvent.instance();
    event.withAppId(appId)
        .withCluster(clusterName)
        .withNamespace(namespaceName)
        .withReleaseId(createdRelease.getId())
        .setNormalPublishEvent(true)
        .setEnv(Env.valueOf(env));

    // 发布 ConfigPublishEvent 事件【重点】
    publisher.publishEvent(event);

    return createdRelease;
  }

  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName, #env)")
  @PostMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/releases")
  public ReleaseDTO createGrayRelease(@PathVariable String appId,
                                      @PathVariable String env, @PathVariable String clusterName,
                                      @PathVariable String namespaceName, @PathVariable String branchName,
                                      @RequestBody NamespaceReleaseModel model) {
    model.setAppId(appId);
    model.setEnv(env);
    model.setClusterName(branchName);
    model.setNamespaceName(namespaceName);

    if (model.isEmergencyPublish() && !portalConfig.isEmergencyPublishAllowed(Env.valueOf(env))) {
      throw new BadRequestException(String.format("Env: %s is not supported emergency publish now", env));
    }

    ReleaseDTO createdRelease = releaseService.publish(model);

    ConfigPublishEvent event = ConfigPublishEvent.instance();
    event.withAppId(appId)
        .withCluster(clusterName)
        .withNamespace(namespaceName)
        .withReleaseId(createdRelease.getId())
        .setGrayPublishEvent(true)
        .setEnv(Env.valueOf(env));

    publisher.publishEvent(event);

    return createdRelease;
  }


  @GetMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/releases/all")
  public List<ReleaseBO> findAllReleases(@PathVariable String appId,
                                         @PathVariable String env,
                                         @PathVariable String clusterName,
                                         @PathVariable String namespaceName,
                                         @Valid @PositiveOrZero(message = "page should be positive or 0") @RequestParam(defaultValue = "0") int page,
                                         @Valid @Positive(message = "size should be positive number") @RequestParam(defaultValue = "5") int size) {
    if (permissionValidator.shouldHideConfigToCurrentUser(appId, env, namespaceName)) {
      return Collections.emptyList();
    }

    return releaseService.findAllReleases(appId, Env.valueOf(env), clusterName, namespaceName, page, size);
  }

  @GetMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/releases/active")
  public List<ReleaseDTO> findActiveReleases(@PathVariable String appId,
                                             @PathVariable String env,
                                             @PathVariable String clusterName,
                                             @PathVariable String namespaceName,
                                             @Valid @PositiveOrZero(message = "page should be positive or 0") @RequestParam(defaultValue = "0") int page,
                                             @Valid @Positive(message = "size should be positive number") @RequestParam(defaultValue = "5") int size) {

    if (permissionValidator.shouldHideConfigToCurrentUser(appId, env, namespaceName)) {
      return Collections.emptyList();
    }

    return releaseService.findActiveReleases(appId, Env.valueOf(env), clusterName, namespaceName, page, size);
  }

  @GetMapping(value = "/envs/{env}/releases/compare")
  public ReleaseCompareResult compareRelease(@PathVariable String env,
                                             @RequestParam long baseReleaseId,
                                             @RequestParam long toCompareReleaseId) {

    return releaseService.compare(Env.valueOf(env), baseReleaseId, toCompareReleaseId);
  }


  @PutMapping(path = "/envs/{env}/releases/{releaseId}/rollback")
  public void rollback(@PathVariable String env,
                       @PathVariable long releaseId) {
    ReleaseDTO release = releaseService.findReleaseById(Env.valueOf(env), releaseId);

    if (release == null) {
      throw new NotFoundException("release not found");
    }

    if (!permissionValidator.hasReleaseNamespacePermission(release.getAppId(), release.getNamespaceName(), env)) {
      throw new AccessDeniedException("Access is denied");
    }

    releaseService.rollback(Env.valueOf(env), releaseId);

    ConfigPublishEvent event = ConfigPublishEvent.instance();
    event.withAppId(release.getAppId())
        .withCluster(release.getClusterName())
        .withNamespace(release.getNamespaceName())
        .withPreviousReleaseId(releaseId)
        .setRollbackEvent(true)
        .setEnv(Env.valueOf(env));

    publisher.publishEvent(event);
  }
}
