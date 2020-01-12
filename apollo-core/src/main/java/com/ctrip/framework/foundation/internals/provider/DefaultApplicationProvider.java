package com.ctrip.framework.foundation.internals.provider;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.framework.foundation.internals.Utils;
import com.ctrip.framework.foundation.internals.io.BOMInputStream;
import com.ctrip.framework.foundation.spi.provider.ApplicationProvider;
import com.ctrip.framework.foundation.spi.provider.Provider;

public class DefaultApplicationProvider implements ApplicationProvider {
  private static final Logger logger = LoggerFactory.getLogger(DefaultApplicationProvider.class);
  public static final String APP_PROPERTIES_CLASSPATH = "/META-INF/app.properties";
  private Properties m_appProperties = new Properties();

  private String m_appId;

  /**
   * 这个初始化方法的意义是为带输入流参数的初始化方法提供数据量对象
   */
  @Override
  public void initialize() {
    try {
      //Thread.currentThread().getContextClassLoader()获得打包的classes目录
      InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(APP_PROPERTIES_CLASSPATH);
      if (in == null) {
        in = DefaultApplicationProvider.class.getResourceAsStream(APP_PROPERTIES_CLASSPATH);
      }

      initialize(in);
    } catch (Throwable ex) {
      logger.error("Initialize DefaultApplicationProvider failed.", ex);
    }
  }

  @Override
  public void initialize(InputStream in) {
    try {
      if (in != null) {
        try {
          m_appProperties.load(new InputStreamReader(new BOMInputStream(in), StandardCharsets.UTF_8));
        } finally {
          in.close();
        }
      }

      initAppId();//初始化appid
    } catch (Throwable ex) {
      logger.error("Initialize DefaultApplicationProvider failed.", ex);
    }
  }

  @Override
  public String getAppId() {
    return m_appId;
  }

  @Override
  public boolean isAppIdSet() {
    return !Utils.isBlank(m_appId);
  }

  @Override
  public String getProperty(String name, String defaultValue) {
    if ("app.id".equals(name)) {
      String val = getAppId();
      return val == null ? defaultValue : val;
    } else {
      String val = m_appProperties.getProperty(name, defaultValue);
      return val == null ? defaultValue : val;
    }
  }

  @Override
  public Class<? extends Provider> getType() {
    return ApplicationProvider.class;
  }

  private void initAppId() {
    // 1. Get app.id from System Property  下面的三种情况设置的变量，通过这种方式即可读取到；
    // 1.1、启动参数  -Dapp.id=YOUR-APP-ID
    // 1.2、Spring Boot application.properties
    // 1.3、System.setProperty("app.id", "YOUR-APP-ID");
    m_appId = System.getProperty("app.id");
    if (!Utils.isBlank(m_appId)) {
      m_appId = m_appId.trim();
      logger.info("App ID is set to {} by app.id property from System Property", m_appId);
      return;
    }

    //2. Try to get app id from OS environment variable   系统的环境变量
    m_appId = System.getenv("APP_ID");
    if (!Utils.isBlank(m_appId)) {
      m_appId = m_appId.trim();
      logger.info("App ID is set to {} by APP_ID property from OS environment variable", m_appId);
      return;
    }

    // 3. Try to get app id from app.properties.    确保classpath:/META-INF/app.properties文件存在，并且其中内容形如：app.id=YOUR-APP-ID
    m_appId = m_appProperties.getProperty("app.id");
    if (!Utils.isBlank(m_appId)) {
      m_appId = m_appId.trim();
      logger.info("App ID is set to {} by app.id property from {}", m_appId, APP_PROPERTIES_CLASSPATH);
      return;
    }

    m_appId = null;
    logger.warn("app.id is not available from System Property and {}. It is set to null", APP_PROPERTIES_CLASSPATH);
  }

  @Override
  public String toString() {
    return "appId [" + getAppId() + "] properties: " + m_appProperties + " (DefaultApplicationProvider)";
  }
}
