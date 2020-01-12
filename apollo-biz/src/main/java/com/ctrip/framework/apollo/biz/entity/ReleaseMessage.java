package com.ctrip.framework.apollo.biz.entity;

import com.google.common.base.MoreObjects;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;

/**
 * @author Jason Song(song_s@ctrip.com)
 * admin server使用
Admin Service 在配置发布后，需要通知所有的 Config Service 有配置发布，从而 Config Service 可以通知对应的客户端来拉取最新的配置。

从概念上来看，这是一个典型的消息使用场景，Admin Service 作为 producer 发出消息，各个Config Service 作为 consumer 消费消息。通过一个消息组件（Message Queue）就能很好的实现 Admin Service 和 Config Service 的解耦。

在实现上，考虑到 Apollo 的实际使用场景，以及为了尽可能减少外部依赖，我们没有采用外部的消息中间件，而是通过数据库实现了一个简单的消息队列。

1、Admin Service 在配置发布后会往 ReleaseMessage 表插入一条消息记录，消息内容就是配置发布的 AppId+Cluster+Namespace ，参见 DatabaseMessageSender 。
2、Config Service 有一个线程会每秒扫描一次 ReleaseMessage 表，看看是否有新的消息记录，参见 ReleaseMessageScanner 。
3、Config Service 如果发现有新的消息记录，那么就会通知到所有的消息监听器（ReleaseMessageListener），如 NotificationControllerV2 ，消息监听器的注册过程参见 ConfigServiceAutoConfiguration 。
4、NotificationControllerV2 得到配置发布的 AppId+Cluster+Namespace 后，会通知对应的客户端。
 */
@Entity
@Table(name = "ReleaseMessage")
public class ReleaseMessage {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "Id")
  private long id;

  @Column(name = "Message", nullable = false)
  private String message;

  @Column(name = "DataChange_LastTime")
  private Date dataChangeLastModifiedTime;

  @PrePersist   //若保存时，未设置该字段，进行补全。
  protected void prePersist() {
    if (this.dataChangeLastModifiedTime == null) {
      dataChangeLastModifiedTime = new Date();
    }
  }

  public ReleaseMessage() {
  }

  public ReleaseMessage(String message) {
    this.message = message;
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .omitNullValues()
        .add("id", id)
        .add("message", message)
        .add("dataChangeLastModifiedTime", dataChangeLastModifiedTime)
        .toString();
  }
}
