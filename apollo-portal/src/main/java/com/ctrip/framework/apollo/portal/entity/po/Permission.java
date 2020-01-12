package com.ctrip.framework.apollo.portal.entity.po;

import com.ctrip.framework.apollo.common.entity.BaseEntity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 * @author Jason Song(song_s@ctrip.com)
 *
默认的会有下面这五个权限也是和应用绑定
public interface PermissionType {

// ========== APP level permission ==========
String CREATE_NAMESPACE = "CreateNamespace"; // 创建 Namespace
String CREATE_CLUSTER = "CreateCluster"; // 创建 Cluster
String ASSIGN_ROLE = "AssignRole"; // 分配用户权限的权限

// ========== namespace level permission =========
String MODIFY_NAMESPACE = "ModifyNamespace"; // 修改 Namespace
String RELEASE_NAMESPACE = "ReleaseNamespace"; // 发布 Namespace

}
 但是在创建应用的时候还是会构建了七个
 是在后面两个基础还是哪个添加了environment


App 级别时，targetId 指向 “App 编号“。
Namespace 级别时，targetId 指向 “App 编号 + Namespace 名字“。
为什么不是 Namespace 的编号？ Namespace 级别，是所有环境 + 所有集群都有权限，所以不能具体某个 Namespace 。
 *
 *
 */
@Entity
@Table(name = "Permission")
@SQLDelete(sql = "Update Permission set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class Permission extends BaseEntity {
  @Column(name = "PermissionType", nullable = false)
  private String permissionType;

  @Column(name = "TargetId", nullable = false)
  private String targetId;

  public String getPermissionType() {
    return permissionType;
  }

  public void setPermissionType(String permissionType) {
    this.permissionType = permissionType;
  }

  public String getTargetId() {
    return targetId;
  }

  public void setTargetId(String targetId) {
    this.targetId = targetId;
  }
}
