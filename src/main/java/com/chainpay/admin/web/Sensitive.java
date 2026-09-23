package com.chainpay.admin.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 敏感的管理操作（动钱、发钥匙、建租户）：会话必须最近再认证过，否则 403 + 3002。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {}
