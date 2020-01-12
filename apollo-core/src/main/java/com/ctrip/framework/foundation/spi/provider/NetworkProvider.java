package com.ctrip.framework.foundation.spi.provider;

/**
 * Provider for network related properties
 * 提供网络相关属性
 */
public interface NetworkProvider extends Provider {
  /**
   * @return the host address, i.e. ip
   */
  public String getHostAddress();

  /**
   * @return the host name
   */
  public String getHostName();
}
