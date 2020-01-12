package com.ctrip.framework.apollo.portal.controller;

import com.ctrip.framework.apollo.portal.spi.SsoHeartbeatHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Since sso auth information has a limited expiry time, so we need to do sso heartbeat to keep the
 * information refreshed when unavailable
 *
 * @author Jason Song(song_s@ctrip.com)
 *
 * 通过打开一个新的窗口，访问 http://ip:prot/sso_hearbeat 地址，每 60 秒刷新一次页面，
 * 从而避免 SSO 登陆过期。因此，相关类的类名都包含 Heartbeat ，代表心跳的意思
 *
 * 前端页面只有一个定时刷新的任务，如何制定请求的是这个？
 *
 * 是通过footer.html中的？
 */
@Controller
@RequestMapping("/sso_heartbeat")
public class SsoHeartbeatController {
  private final SsoHeartbeatHandler handler;

  public SsoHeartbeatController(final SsoHeartbeatHandler handler) {
    this.handler = handler;
  }

  @GetMapping
  public void heartbeat(HttpServletRequest request, HttpServletResponse response) {
    handler.doHeartbeat(request, response);
  }
}
