package com.mogum.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.web.origin=https://mogum.example",
        "app.web.cookie-domain=mogum.example",
        // 단일 스레드를 재사용해 모든 HTTP 로그 검사에서 MDC 잔류를 관찰한다.
        "server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"
})
@AutoConfigureRestTestClient
@Import({TestClockConfiguration.class, IntegrationTestConfiguration.class})
@ContextConfiguration(initializers = {PostgresTestConfiguration.ConnectionInitializer.class,
        IntegrationTestConfiguration.OAuthInitializer.class})
@ExtendWith(DatabaseCleaner.class)
public @interface IntegrationTest {
}
