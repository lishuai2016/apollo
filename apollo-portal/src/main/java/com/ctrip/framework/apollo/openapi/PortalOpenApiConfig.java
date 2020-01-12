package com.ctrip.framework.apollo.openapi;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 Apollo 提供了一套的 Http REST 接口，使第三方应用能够自己管理配置。
 虽然 Apollo 系统本身提供了 Portal 来管理配置，但是在有些情景下，应用需要通过程序去管理配置。

 OpenAPI 和 Portal 都在 apollo-portal 项目中，但是他们是两套 API ，包括 package 都是两个不同的包
 */


@EnableAutoConfiguration
@Configuration
@ComponentScan(basePackageClasses = PortalOpenApiConfig.class)
public class PortalOpenApiConfig {

}
