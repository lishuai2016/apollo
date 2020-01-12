package com.ctrip.framework.apollo.demo.spring.common.config;

import com.ctrip.framework.apollo.spring.annotation.EnableApolloConfig;

import org.springframework.context.annotation.Configuration;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@Configuration
@EnableApolloConfig(value = "application", order = 10)//开启注解方式，并指定命名空间
public class AppConfig {
}
