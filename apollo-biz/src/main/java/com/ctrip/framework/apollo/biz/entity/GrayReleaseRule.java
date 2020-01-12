package com.ctrip.framework.apollo.biz.entity;

import com.ctrip.framework.apollo.common.entity.BaseEntity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 1、对于一个子 Namespace 仅对应一条有效灰度规则 GrayReleaseRule 记录。
 每次变更灰度规则时，标记删除老的灰度规则，新增保存新的灰度规则。

 2、变更灰度配置完成后，会发布一条 ReleaseMessage 消息，以通知配置变更。
 */

@Entity
@Table(name = "GrayReleaseRule")
@SQLDelete(sql = "Update GrayReleaseRule set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class GrayReleaseRule extends BaseEntity{

  @Column(name = "appId", nullable = false)
  private String appId;

  @Column(name = "ClusterName", nullable = false)
  private String clusterName;

  @Column(name = "NamespaceName", nullable = false)
  private String namespaceName;

  @Column(name = "BranchName", nullable = false)//使用子 Cluster 名字
  private String branchName;

  //规则，目前将 {@link com.ctrip.framework.apollo.common.dto.GrayReleaseRuleItemDTO} 的数组，JSON 格式化
  @Column(name = "Rules")
  private String rules;

  /**
   * Release 编号。
   *
   * 有两种情况：
   * 1、当灰度已经发布，则指向对应的最新的 Release 对象的编号
   * 2、当灰度还未发布，等于 0 。等到灰度发布后，更新为对应的 Release 对象的编号
   */
  @Column(name = "releaseId", nullable = false)
  private Long releaseId;

  /**
   * 分支状态，在 {@link com.ctrip.framework.apollo.common.constants.NamespaceBranchStatus} 枚举
   */
  @Column(name = "BranchStatus", nullable = false)
  private int branchStatus;

  public String getAppId() {
    return appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public String getClusterName() {
    return clusterName;
  }

  public void setClusterName(String clusterName) {
    this.clusterName = clusterName;
  }

  public String getNamespaceName() {
    return namespaceName;
  }

  public void setNamespaceName(String namespaceName) {
    this.namespaceName = namespaceName;
  }

  public String getBranchName() {
    return branchName;
  }

  public void setBranchName(String branchName) {
    this.branchName = branchName;
  }

  public String getRules() {
    return rules;
  }

  public void setRules(String rules) {
    this.rules = rules;
  }

  public Long getReleaseId() {
    return releaseId;
  }

  public void setReleaseId(Long releaseId) {
    this.releaseId = releaseId;
  }

  public int getBranchStatus() {
    return branchStatus;
  }

  public void setBranchStatus(int branchStatus) {
    this.branchStatus = branchStatus;
  }
}
