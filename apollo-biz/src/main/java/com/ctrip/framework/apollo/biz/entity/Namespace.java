package com.ctrip.framework.apollo.biz.entity;

import com.ctrip.framework.apollo.common.entity.BaseEntity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 Cluster Namespace 实体，是配置项的集合，类似于一个配置文件的概念

 数据流向如下：

 1、在 App 下创建 AppNamespace 后，自动给 App 下每个 Cluster 创建 Namespace 。
 2、在 App 下创建 Cluster 后，根据 App 下 每个 AppNamespace 创建 Namespace 。
 3、可删除 Cluster 下的 Namespace 。

 总结来说：

 1、AppNamespace 是 App 下的每个 Cluster 默认创建的 Namespace 。
 2、Namespace 是 每个 Cluster 实际拥有的 Namespace 。



 Namespace 类型有三种：

 1、私有类型：私有类型的 Namespace 具有 private 权限。
 2、公共类型：公共类型的 Namespace 具有 public 权限。公共类型的 Namespace 相当于游离于应用之外的配置，且通过 Namespace 的名称去标识公共 Namespace ，所以公共的 Namespace 的名称必须全局唯一。
 3、关联类型：关联类型又可称为继承类型，关联类型具有 private 权限。关联类型的Namespace 继承于公共类型的Namespace，用于覆盖公共 Namespace 的某些配置。
 */

@Entity
@Table(name = "Namespace")
@SQLDelete(sql = "Update Namespace set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class Namespace extends BaseEntity {

  @Column(name = "appId", nullable = false)
  private String appId;

  @Column(name = "ClusterName", nullable = false)
  private String clusterName;

  @Column(name = "NamespaceName", nullable = false)
  private String namespaceName;

  public Namespace(){

  }

  public Namespace(String appId, String clusterName, String namespaceName) {
    this.appId = appId;
    this.clusterName = clusterName;
    this.namespaceName = namespaceName;
  }

  public String getAppId() {
    return appId;
  }

  public String getClusterName() {
    return clusterName;
  }

  public String getNamespaceName() {
    return namespaceName;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public void setClusterName(String clusterName) {
    this.clusterName = clusterName;
  }

  public void setNamespaceName(String namespaceName) {
    this.namespaceName = namespaceName;
  }

  public String toString() {
    return toStringHelper().add("appId", appId).add("clusterName", clusterName)
        .add("namespaceName", namespaceName).toString();
  }
}
