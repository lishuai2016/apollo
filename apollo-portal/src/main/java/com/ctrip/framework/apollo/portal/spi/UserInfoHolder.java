package com.ctrip.framework.apollo.portal.spi;

import com.ctrip.framework.apollo.portal.entity.bo.UserInfo;

/**
 * Get access to the user's information,
 * different companies should have a different implementation
 * 一般都是把当前登录用户信息放在线程 ThreadLocal
 */
public interface UserInfoHolder {

  UserInfo getUser();

}
