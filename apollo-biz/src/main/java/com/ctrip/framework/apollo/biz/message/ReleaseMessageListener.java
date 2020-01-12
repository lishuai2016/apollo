package com.ctrip.framework.apollo.biz.message;

import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;

/**
 * @author Jason Song(song_s@ctrip.com)
 * ReleaseMessage 监听器接口
 * NotificationControllerV2 得到配置发布的 AppId+Cluster+Namespace 后，会通知对应的客户端
 */
public interface ReleaseMessageListener {
  void handleMessage(ReleaseMessage message, String channel);
}
