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
 * 添加一个应用的时候好像这个表只是关联了前三个角色
 *
 */
@Entity
@Table(name = "UserRole")
@SQLDelete(sql = "Update UserRole set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class UserRole extends BaseEntity {
  @Column(name = "UserId", nullable = false)
  private String userId;

  @Column(name = "RoleId", nullable = false)
  private long roleId;

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public long getRoleId() {
    return roleId;
  }

  public void setRoleId(long roleId) {
    this.roleId = roleId;
  }
}
