package com.mogum.support;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로컬 XSRF 쿠키의 발급/로그아웃 만료에 Domain이 없는지는 실제 보안 필터의 응답으로 확인한다.
 * CookieCsrfTokenRepository의 customizer가 제품 필터 체인 안에 있어 설정 바인딩/세션 직렬화기
 * 단위 검사만으로는 이 사실을 확인할 수 없다. 공용 묶음과 달라야 하는 값은 이 하나뿐이다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@IntegrationTest
@TestPropertySource(properties = "app.web.cookie-domain=")
public @interface LocalCookieIntegrationTest {
}
