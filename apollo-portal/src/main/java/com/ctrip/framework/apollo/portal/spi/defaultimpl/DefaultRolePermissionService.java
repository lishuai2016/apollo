package com.ctrip.framework.apollo.portal.spi.defaultimpl;

import com.ctrip.framework.apollo.openapi.repository.ConsumerRoleRepository;
import com.ctrip.framework.apollo.portal.component.config.PortalConfig;
import com.ctrip.framework.apollo.portal.entity.bo.UserInfo;
import com.ctrip.framework.apollo.portal.entity.po.Permission;
import com.ctrip.framework.apollo.portal.entity.po.Role;
import com.ctrip.framework.apollo.portal.entity.po.RolePermission;
import com.ctrip.framework.apollo.portal.entity.po.UserRole;
import com.ctrip.framework.apollo.portal.repository.PermissionRepository;
import com.ctrip.framework.apollo.portal.repository.RolePermissionRepository;
import com.ctrip.framework.apollo.portal.repository.RoleRepository;
import com.ctrip.framework.apollo.portal.repository.UserRoleRepository;
import com.ctrip.framework.apollo.portal.service.RolePermissionService;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Created by timothy on 2017/4/26.
 *
 * 角色权限服务
 * 1、Role 相关
 * 2、UserRole 相关
 * 3、UserPermission 相关
 * 4、Permission 相关
 * 5、rolePermission
 */
public class DefaultRolePermissionService implements RolePermissionService {
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private PortalConfig portalConfig;
    @Autowired
    private ConsumerRoleRepository consumerRoleRepository;

    /**
     * Create role with permissions, note that role name should be unique
     * 【rolePermission角色和权限的关系】保存一个角色以及该角色对应的权限
     */
    @Transactional
    public Role createRoleWithPermissions(Role role, Set<Long> permissionIds) {
        Role current = findRoleByRoleName(role.getRoleName());
        Preconditions.checkState(current == null, "Role %s already exists!", role.getRoleName());
// 新增 Role
        Role createdRole = roleRepository.save(role);
// 授权给 Role
        if (!CollectionUtils.isEmpty(permissionIds)) {
            // 创建 RolePermission 数组
            Iterable<RolePermission> rolePermissions = permissionIds.stream().map(permissionId -> {
                RolePermission rolePermission = new RolePermission();
                rolePermission.setRoleId(createdRole.getId());
                rolePermission.setPermissionId(permissionId);
                rolePermission.setDataChangeCreatedBy(createdRole.getDataChangeCreatedBy());
                rolePermission.setDataChangeLastModifiedBy(createdRole.getDataChangeLastModifiedBy());
                return rolePermission;
            }).collect(Collectors.toList());
            // 保存 RolePermission 数组
            rolePermissionRepository.saveAll(rolePermissions);
        }

        return createdRole;
    }

    /**
     * Assign role to users
     *
     * @return the users assigned roles
     *
     * 新增这个角色对应的用户有哪些，会排除那些数据库中已经存在的那些
     *
     * 简单来说就是给角色添加用户
     *
     * operatorUserId 表示的是执行这个操作的人是谁
     * 【userRole】
     */
    @Transactional
    public Set<String> assignRoleToUsers(String roleName, Set<String> userIds,
                                         String operatorUserId) {
        Role role = findRoleByRoleName(roleName);
        Preconditions.checkState(role != null, "Role %s doesn't exist!", roleName);

        List<UserRole> existedUserRoles =
                userRoleRepository.findByUserIdInAndRoleId(userIds, role.getId());//查看数据库中该角色存在的用户id
        Set<String> existedUserIds =
            existedUserRoles.stream().map(UserRole::getUserId).collect(Collectors.toSet());

        // 排除已经存在的
        Set<String> toAssignUserIds = Sets.difference(userIds, existedUserIds);

        // 创建需要新增的 UserRole 数组
        Iterable<UserRole> toCreate = toAssignUserIds.stream().map(userId -> {
            UserRole userRole = new UserRole();
            userRole.setRoleId(role.getId());
            userRole.setUserId(userId);
            userRole.setDataChangeCreatedBy(operatorUserId);
            userRole.setDataChangeLastModifiedBy(operatorUserId);
            return userRole;
        }).collect(Collectors.toList());

        userRoleRepository.saveAll(toCreate);//保存用户角色数组
        return toAssignUserIds;
    }

    /**
     * Remove role from users
     * 对应上面的那个方法，删除指定角色下的用户id
     *
     * 这里执行的是标记删除
     */
    @Transactional
    public void removeRoleFromUsers(String roleName, Set<String> userIds, String operatorUserId) {
        Role role = findRoleByRoleName(roleName);
        Preconditions.checkState(role != null, "Role %s doesn't exist!", roleName);

        List<UserRole> existedUserRoles =
                userRoleRepository.findByUserIdInAndRoleId(userIds, role.getId());

        for (UserRole userRole : existedUserRoles) {
            userRole.setDeleted(true);
            userRole.setDataChangeLastModifiedTime(new Date());
            userRole.setDataChangeLastModifiedBy(operatorUserId);
        }
        // 保存 RolePermission 数组 【标记删除】
        userRoleRepository.saveAll(existedUserRoles);
    }

    /**
     * Query users with role
     * 根据角色名称来获得对应的用户有哪些，这里返回的直属userID
     */
    public Set<UserInfo> queryUsersWithRole(String roleName) {
        Role role = findRoleByRoleName(roleName);

        if (role == null) {
            return Collections.emptySet();
        }

        List<UserRole> userRoles = userRoleRepository.findByRoleId(role.getId());

        Set<UserInfo> users = userRoles.stream().map(userRole -> {
            UserInfo userInfo = new UserInfo();
            userInfo.setUserId(userRole.getUserId());
            return userInfo;
        }).collect(Collectors.toSet());

        return users;
    }

    /**
     * Find role by role name, note that roleName should be unique
     * 根据角色名称获得角色对象
     */
    public Role findRoleByRoleName(String roleName) {
        return roleRepository.findTopByRoleName(roleName);
    }

    /**
     * Check whether user has the permission
     * 判断用户是否有指定的权限
     */
    public boolean userHasPermission(String userId, String permissionType, String targetId) {
        //1、首先判断对应的权限是否存在
        Permission permission =
                permissionRepository.findTopByPermissionTypeAndTargetId(permissionType, targetId);
        if (permission == null) {
            return false;
        }

        if (isSuperAdmin(userId)) {//判断是否为超级管理员
            return true;
        }
        //2、根据useID获得用户的角色
        List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
        if (CollectionUtils.isEmpty(userRoles)) {
            return false;
        }
        //3、根据角色id获得对应的权限
        Set<Long> roleIds =
            userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toSet());
        List<RolePermission> rolePermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        if (CollectionUtils.isEmpty(rolePermissions)) {
            return false;
        }

        for (RolePermission rolePermission : rolePermissions) {
            if (rolePermission.getPermissionId() == permission.getId()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获得用户所具有的角色有哪些
     * @param userId
     * @return
     */
    @Override
    public List<Role> findUserRoles(String userId) {
        List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
        if (CollectionUtils.isEmpty(userRoles)) {
            return Collections.emptyList();
        }

        Set<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toSet());

        return Lists.newLinkedList(roleRepository.findAllById(roleIds));
    }

    /**
     * 判断用户是不是超级管理员
     *
     * 通过 ServerConfig 的 "superAdmin" 配置项，判断是否存在该账号。
     *
     * @param userId
     * @return
     */
    public boolean isSuperAdmin(String userId) {
        return portalConfig.superAdmins().contains(userId);
    }

    /**
     * Create permission, note that permissionType + targetId should be unique
     *
     * 根据permissionType + targetId唯一标识一个权限
     */
    @Transactional
    public Permission createPermission(Permission permission) {
        String permissionType = permission.getPermissionType();
        String targetId = permission.getTargetId();
        Permission current =
                permissionRepository.findTopByPermissionTypeAndTargetId(permissionType, targetId);
        Preconditions.checkState(current == null,
                "Permission with permissionType %s targetId %s already exists!", permissionType, targetId);

        return permissionRepository.save(permission);
    }

    /**
     * Create permissions, note that permissionType + targetId should be unique
     * 批量创建权限
     */
    @Transactional
    public Set<Permission> createPermissions(Set<Permission> permissions) {
        Multimap<String, String> targetIdPermissionTypes = HashMultimap.create();
        for (Permission permission : permissions) {
            targetIdPermissionTypes.put(permission.getTargetId(), permission.getPermissionType());
        }

        for (String targetId : targetIdPermissionTypes.keySet()) {
            Collection<String> permissionTypes = targetIdPermissionTypes.get(targetId);
            List<Permission> current =
                    permissionRepository.findByPermissionTypeInAndTargetId(permissionTypes, targetId);
            Preconditions.checkState(CollectionUtils.isEmpty(current),
                    "Permission with permissionType %s targetId %s already exists!", permissionTypes,
                    targetId);
        }

        Iterable<Permission> results = permissionRepository.saveAll(permissions);
        return StreamSupport.stream(results.spliterator(), false).collect(Collectors.toSet());
    }

    /**
     * 删除应用管理的角色和权限信息
     * @param appId
     * @param operator
     */
    @Transactional
    @Override
    public void deleteRolePermissionsByAppId(String appId, String operator) {
        List<Long> permissionIds = permissionRepository.findPermissionIdsByAppId(appId);//这个appId 映射为targetID来查询

        if (!permissionIds.isEmpty()) {
            // 1. delete Permission
            permissionRepository.batchDelete(permissionIds, operator);

            // 2. delete Role Permission
            rolePermissionRepository.batchDeleteByPermissionIds(permissionIds, operator);
        }

        List<Long> roleIds = roleRepository.findRoleIdsByAppId(appId);//拼接角色的名字查询角色信息

        if (!roleIds.isEmpty()) {
            // 3. delete Role
            roleRepository.batchDelete(roleIds, operator);

            // 4. delete User Role
            userRoleRepository.batchDeleteByRoleIds(roleIds, operator);

            // 5. delete Consumer Role
            consumerRoleRepository.batchDeleteByRoleIds(roleIds, operator);
        }
    }

    /**
     * 和上面类似
     * @param appId
     * @param namespaceName
     * @param operator
     */
    @Transactional
    @Override
    public void deleteRolePermissionsByAppIdAndNamespace(String appId, String namespaceName, String operator) {
        List<Long> permissionIds = permissionRepository.findPermissionIdsByAppIdAndNamespace(appId, namespaceName);

        if (!permissionIds.isEmpty()) {
            // 1. delete Permission
            permissionRepository.batchDelete(permissionIds, operator);

            // 2. delete Role Permission
            rolePermissionRepository.batchDeleteByPermissionIds(permissionIds, operator);
        }

        List<Long> roleIds = roleRepository.findRoleIdsByAppIdAndNamespace(appId, namespaceName);

        if (!roleIds.isEmpty()) {
            // 3. delete Role
            roleRepository.batchDelete(roleIds, operator);

            // 4. delete User Role
            userRoleRepository.batchDeleteByRoleIds(roleIds, operator);

            // 5. delete Consumer Role
            consumerRoleRepository.batchDeleteByRoleIds(roleIds, operator);
        }
    }
}
