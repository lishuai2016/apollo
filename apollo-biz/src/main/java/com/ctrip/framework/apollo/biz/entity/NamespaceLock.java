package com.ctrip.framework.apollo.biz.entity;

import com.ctrip.framework.apollo.common.entity.BaseEntity;

import org.hibernate.annotations.Where;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 可通过设置 ConfigDB 的 ServerConfig 的 "namespace.lock.switch" 为 "true" 开启。效果如下：

1、一次配置修改只能是一个人
 2、一次配置发布只能是另一个人
 也就是说，开启后，一次配置修改并发布，需要两个人。
 默认为 "false" ，即关闭。

 写操作 Item 时，创建 Namespace 对应的 NamespaceLock 记录到 ConfigDB 数据库中，从而记录配置修改人。

 */

@Entity
@Table(name = "NamespaceLock")
@Where(clause = "isDeleted = 0")
public class NamespaceLock extends BaseEntity{

  //namespaceId 字段，Namespace 编号，指向对应的 Namespace
  //该字段上有唯一索引。通过该锁定，保证并发写操作时，同一个 Namespace 有且仅有创建一条 NamespaceLock 记录。
  @Column(name = "NamespaceId")
  private long namespaceId;

  public long getNamespaceId() {
    return namespaceId;
  }

  public void setNamespaceId(long namespaceId) {
    this.namespaceId = namespaceId;
  }
}
