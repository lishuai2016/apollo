package com.ctrip.framework.apollo.openapi.auth;

import com.ctrip.framework.apollo.openapi.service.ConsumerRolePermissionService;
import com.ctrip.framework.apollo.openapi.util.ConsumerAuthUtil;
import com.ctrip.framework.apollo.portal.constant.PermissionType;
import com.ctrip.framework.apollo.portal.util.RoleUtils;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 Consumer 权限校验器
 ConsumerPermissionValidator 和 PermissionValidator 基本类似

 在每个需要校验权限的方法上，添加 @PreAuthorize 注解，并在 value 属性上写 EL 表达式，调用 PermissionValidator 的校验方法。例如：

 1、创建 Namespace 的方法，添加了 @PreAuthorize(value = "@consumerPermissionValidator.hasCreateNamespacePermission(#request, #appId)") 。
 2、发布 Namespace 的方法，添加了 @PreAuthorize(value = "@consumerPermissionValidator.hasReleaseNamespacePermission(#request, #appId, #namespaceName)") 。
 通过这样的方式，达到功能 + 数据级的权限控制。
 */

@Component
public class ConsumerPermissionValidator {

  private final ConsumerRolePermissionService permissionService;
  private final ConsumerAuthUtil consumerAuthUtil;

  public ConsumerPermissionValidator(
      final ConsumerRolePermissionService permissionService,
      final ConsumerAuthUtil consumerAuthUtil) {
    this.permissionService = permissionService;
    this.consumerAuthUtil = consumerAuthUtil;
  }

  public boolean hasModifyNamespacePermission(HttpServletRequest request, String appId, String namespaceName,
      String env) {
    if (hasCreateNamespacePermission(request, appId)) {
      return true;
    }
    return permissionService.consumerHasPermission(consumerAuthUtil.retrieveConsumerId(request),
        PermissionType.MODIFY_NAMESPACE, RoleUtils.buildNamespaceTargetId(appId, namespaceName))
        ||
        permissionService.consumerHasPermission(consumerAuthUtil.retrieveConsumerId(request),
        PermissionType.MODIFY_NAMESPACE, RoleUtils.buildNamespaceTargetId(appId, namespaceName, env));

  }

  public boolean hasReleaseNamespacePermission(HttpServletRequest request, String appId, String namespaceName,
      String env) {
    if (hasCreateNamespacePermission(request, appId)) {
      return true;
    }
    return permissionService.consumerHasPermission(consumerAuthUtil.retrieveConsumerId(request),
        PermissionType.RELEASE_NAMESPACE,
        RoleUtils.buildNamespaceTargetId(appId, namespaceName))
        ||
        permissionService.consumerHasPermission(consumerAuthUtil.retrieveConsumerId(request),
        PermissionType.RELEASE_NAMESPACE,
        RoleUtils.buildNamespaceTargetId(appId, namespaceName, env));

  }

  public boolean hasCreateNamespacePermission(HttpServletRequest request, String appId) {
    return permissionService.consumerHasPermission(consumerAuthUtil.retrieveConsumerId(request),
                                                   PermissionType.CREATE_NAMESPACE,
                                                   appId);
  }

}
