package com.ctrip.framework.apollo.core.dto;

/**
 * @author Jason Song(song_s@ctrip.com)
 *
 * 1、namespaceName 字段，Namespace 名，指向对应的 Namespace 。
 * 因此，一个 Namespace 对应一个 ApolloConfigNotification 对象。
 *
 * 2、notificationId 字段，最新通知编号，目前使用 ReleaseMessage.id 字段。
 *
 * 3、messages 字段，通知消息集合。volatile 修饰，因为存在多线程修改和读取。

为什么 ApolloConfigNotification 中有 ApolloNotificationMessages ，
而且 ApolloNotificationMessages 的 details 字段是 Map ？
按道理说，对于一个 Namespace 的通知，使用 ApolloConfigNotification 的 namespaceName + notificationId 已经足够了。
但是，在 namespaceName 对应的 Namespace 是关联类型时，会同时查询当前 Namespace + 关联的 Namespace 这两个 Namespace，
所以会是多个，使用 Map 数据结构。
当然，对于 /notifications/v2 接口，仅有【直接】获得到配置变化才可能出现 ApolloNotificationMessages.details 为多个的情况。
为啥？在 #handleMessage(...) 方法中，一次只处理一条 ReleaseMessage ，因此只会有 ApolloNotificationMessages.details 只会有一个。

 */
public class ApolloConfigNotification {
  private String namespaceName;
  private long notificationId;
  private volatile ApolloNotificationMessages messages;

  //for json converter
  public ApolloConfigNotification() {
  }

  public ApolloConfigNotification(String namespaceName, long notificationId) {
    this.namespaceName = namespaceName;
    this.notificationId = notificationId;
  }

  public String getNamespaceName() {
    return namespaceName;
  }

  public long getNotificationId() {
    return notificationId;
  }

  public void setNamespaceName(String namespaceName) {
    this.namespaceName = namespaceName;
  }

  public ApolloNotificationMessages getMessages() {
    return messages;
  }

  public void setMessages(ApolloNotificationMessages messages) {
    this.messages = messages;
  }

  public void addMessage(String key, long notificationId) {
    if (this.messages == null) {
      synchronized (this) {
        if (this.messages == null) {
          // 创建 ApolloNotificationMessages 对象
          this.messages = new ApolloNotificationMessages();
        }
      }
    }
    this.messages.put(key, notificationId);
  }

  @Override
  public String toString() {
    return "ApolloConfigNotification{" +
        "namespaceName='" + namespaceName + '\'' +
        ", notificationId=" + notificationId +
        '}';
  }
}
