package com.ctrip.framework.apollo.common.entity;

import com.ctrip.framework.apollo.common.utils.InputValidator;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 1、ORM 选用 Hibernate 框架。
 2、@SQLDelete(...) + @Where(...) 注解，配合 BaseEntity.extends 字段，实现 App 的逻辑删除

 我们看到 App 创建时，在 Portal Service 存储完成后，会异步同步到 Admin Service 中，这是为什么呢？

 在 Apollo 的架构中，一个环境( Env ) 对应一套 Admin Service 和 Config Service 。
 而 Portal Service 会管理所有环境( Env ) 。因此，每次创建 App 后，需要进行同步。

 或者说，App 在 Portal Service 中，表示需要管理的 App 。而在 Admin Service 和 Config Service 中，表示存在的 App

 */

@Entity
@Table(name = "App")
@SQLDelete(sql = "Update App set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class App extends BaseEntity {

  @NotBlank(message = "Name cannot be blank")
  @Column(name = "Name", nullable = false)
  private String name;

  @NotBlank(message = "AppId cannot be blank")
  @Pattern(
      regexp = InputValidator.CLUSTER_NAMESPACE_VALIDATOR,
      message = InputValidator.INVALID_CLUSTER_NAMESPACE_MESSAGE
  )
  @Column(name = "AppId", nullable = false)
  private String appId;

  @Column(name = "OrgId", nullable = false)
  private String orgId;

  @Column(name = "OrgName", nullable = false)
  private String orgName;

  @NotBlank(message = "OwnerName cannot be blank")
  @Column(name = "OwnerName", nullable = false)
  private String ownerName;

  @NotBlank(message = "OwnerEmail cannot be blank")
  @Column(name = "OwnerEmail", nullable = false)
  private String ownerEmail;

  public String getAppId() {
    return appId;
  }

  public String getName() {
    return name;
  }

  public String getOrgId() {
    return orgId;
  }

  public String getOrgName() {
    return orgName;
  }

  public String getOwnerEmail() {
    return ownerEmail;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setOrgId(String orgId) {
    this.orgId = orgId;
  }

  public void setOrgName(String orgName) {
    this.orgName = orgName;
  }

  public void setOwnerEmail(String ownerEmail) {
    this.ownerEmail = ownerEmail;
  }

  public void setOwnerName(String ownerName) {
    this.ownerName = ownerName;
  }

  public String toString() {
    return toStringHelper().add("name", name).add("appId", appId)
        .add("orgId", orgId)
        .add("orgName", orgName)
        .add("ownerName", ownerName)
        .add("ownerEmail", ownerEmail).toString();
  }

  public static class Builder {

    public Builder() {
    }

    private App app = new App();

    public Builder name(String name) {
      app.setName(name);
      return this;
    }

    public Builder appId(String appId) {
      app.setAppId(appId);
      return this;
    }

    public Builder orgId(String orgId) {
      app.setOrgId(orgId);
      return this;
    }

    public Builder orgName(String orgName) {
      app.setOrgName(orgName);
      return this;
    }

    public Builder ownerName(String ownerName) {
      app.setOwnerName(ownerName);
      return this;
    }

    public Builder ownerEmail(String ownerEmail) {
      app.setOwnerEmail(ownerEmail);
      return this;
    }

    public App build() {
      return app;
    }

  }

  public static Builder builder() {
    return new Builder();
  }


}
