package com.ctrip.framework.apollo.biz.message;

/**
 * @author Jason Song(song_s@ctrip.com)
 * 发送消息接口
 */
public interface MessageSender {
  void sendMessage(String message, String channel);
}
