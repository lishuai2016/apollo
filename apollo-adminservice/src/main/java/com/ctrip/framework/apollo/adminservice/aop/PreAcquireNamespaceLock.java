package com.ctrip.framework.apollo.adminservice.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标识方法需要获取到namespace的lock才能执行
 *
 * 上面两个方法是对这个注解的解析
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreAcquireNamespaceLock {

}
