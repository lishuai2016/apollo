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
每次创建应用后生成下面五个角色，和应用时相关的

oleName 字段，角色名，通过系统自动生成。目前有三种类型( 不是三个 )角色+ 2：
1、App 管理员，格式为 "Master + AppId" ，例如："Master+100004458" 。
2、Namespace 修改管理员，格式为 "ModifyNamespace + AppId + NamespaceName" ，例如："ModifyNamespace+100004458+application" 。
3、Namespace 发布管理员，格式为 "ReleaseNamespace + AppId + NamespaceName" ，例如："ReleaseNamespace+100004458+application" 。

 后面的两个是在2和3的基础上+environment，比如：
ModifyNamespace+test2+application+DEV
ReleaseNamespace+test2+application+DEV

 */
@Entity
@Table(name = "Role")
@SQLDelete(sql = "Update Role set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class Role extends BaseEntity {
  @Column(name = "RoleName", nullable = false)
  private String roleName;

  public String getRoleName() {
    return roleName;
  }

  public void setRoleName(String roleName) {
    this.roleName = roleName;
  }
}
