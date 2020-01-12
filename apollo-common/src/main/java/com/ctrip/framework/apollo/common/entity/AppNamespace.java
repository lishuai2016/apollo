package com.ctrip.framework.apollo.common.entity;


import com.ctrip.framework.apollo.common.utils.InputValidator;
import com.ctrip.framework.apollo.core.enums.ConfigFileFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 1、appId 字段，App 编号，指向对应的 App 。App : AppNamespace = 1 : N 。
 format 字段，格式。在 com.ctrip.framework.apollo.core.enums.ConfigFileFormat 枚举类中，定义了五种类型。
 Properties("properties"), XML("xml"), JSON("json"), YML("yml"), YAML("yaml"), TXT("txt");

 2、Namespace的获取权限分为两种：

 private （私有的）：private 权限的 Namespace ，只能被所属的应用获取到。
 一个应用尝试获取其它应用 private 的 Namespace ，Apollo 会报 “404” 异常。

 public （公共的）：public 权限的 Namespace ，能被任何应用获取。
 这里的获取权限是相对于 Apollo 客户端来说的

 */


@Entity
@Table(name = "AppNamespace")
@SQLDelete(sql = "Update AppNamespace set isDeleted = 1 where id = ?")
@Where(clause = "isDeleted = 0")
public class AppNamespace extends BaseEntity {

  @NotBlank(message = "App Name cannot be blank")
  @Pattern(
      regexp = InputValidator.CLUSTER_NAMESPACE_VALIDATOR,
      message = "Namespace格式错误: " + InputValidator.INVALID_CLUSTER_NAMESPACE_MESSAGE + " & " + InputValidator.INVALID_NAMESPACE_NAMESPACE_MESSAGE
  )
  @Column(name = "Name", nullable = false)
  private String name;

  @NotBlank(message = "AppId cannot be blank")
  @Column(name = "AppId", nullable = false)
  private String appId;

  @Column(name = "Format", nullable = false)
  private String format;

  @Column(name = "IsPublic", columnDefinition = "Bit default '0'")
  private boolean isPublic = false;

  @Column(name = "Comment")
  private String comment;

  public String getAppId() {
    return appId;
  }

  public String getComment() {
    return comment;
  }

  public String getName() {
    return name;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean isPublic() {
    return isPublic;
  }

  public void setPublic(boolean aPublic) {
    isPublic = aPublic;
  }

  public ConfigFileFormat formatAsEnum() {
    return ConfigFileFormat.fromString(this.format);
  }

  public String getFormat() {
    return format;
  }

  public void setFormat(String format) {
    this.format = format;
  }

  public String toString() {
    return toStringHelper().add("name", name).add("appId", appId).add("comment", comment)
        .add("format", format).add("isPublic", isPublic).toString();
  }
}
