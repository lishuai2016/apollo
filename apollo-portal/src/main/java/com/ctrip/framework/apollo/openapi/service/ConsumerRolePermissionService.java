package com.ctrip.framework.apollo.openapi.service;

import com.ctrip.framework.apollo.openapi.entity.ConsumerRole;
import com.ctrip.framework.apollo.openapi.repository.ConsumerRoleRepository;
import com.ctrip.framework.apollo.portal.entity.po.Permission;
import com.ctrip.framework.apollo.portal.entity.po.RolePermission;
import com.ctrip.framework.apollo.portal.repository.PermissionRepository;
import com.ctrip.framework.apollo.portal.repository.RolePermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Jason Song(song_s@ctrip.com)
 * ConsumerRole 权限校验 Service
 */
@Service
public class ConsumerRolePermissionService {
  private final PermissionRepository permissionRepository;
  private final ConsumerRoleRepository consumerRoleRepository;
  private final RolePermissionRepository rolePermissionRepository;

  public ConsumerRolePermissionService(
      final PermissionRepository permissionRepository,
      final ConsumerRoleRepository consumerRoleRepository,
      final RolePermissionRepository rolePermissionRepository) {
    this.permissionRepository = permissionRepository;
    this.consumerRoleRepository = consumerRoleRepository;
    this.rolePermissionRepository = rolePermissionRepository;
  }

  /**
   * Check whether user has the permission
   */
  public boolean consumerHasPermission(long consumerId, String permissionType, String targetId) {
    //1、首先查询数据库，看参数对应的权限是否存在
    Permission permission =
        permissionRepository.findTopByPermissionTypeAndTargetId(permissionType, targetId);
    if (permission == null) {
      return false;
    }
    //2、查询用户的角色
    List<ConsumerRole> consumerRoles = consumerRoleRepository.findByConsumerId(consumerId);
    if (CollectionUtils.isEmpty(consumerRoles)) {
      return false;
    }

    Set<Long> roleIds =
        consumerRoles.stream().map(ConsumerRole::getRoleId).collect(Collectors.toSet());
    //3、根据角色获得角色权限
    List<RolePermission> rolePermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
    if (CollectionUtils.isEmpty(rolePermissions)) {
      return false;
    }
    //4、查看角色对应的权限和用户传入的参数对应的权限是否一致
    for (RolePermission rolePermission : rolePermissions) {
      if (rolePermission.getPermissionId() == permission.getId()) {
        return true;
      }
    }

    return false;
  }
}
