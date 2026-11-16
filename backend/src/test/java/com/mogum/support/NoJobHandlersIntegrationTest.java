package com.mogum.support;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 운영과 같이 받기 처리기 빈이 없는 HTTP 구성을 검증하는 별도 묶음이다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@TestPropertySource(properties = "test.job-intake-handlers=false")
public @interface NoJobHandlersIntegrationTest { }
