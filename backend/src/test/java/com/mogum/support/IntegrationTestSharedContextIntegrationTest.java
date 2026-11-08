package com.mogum.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContextManager;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class IntegrationTestSharedContextIntegrationTest {
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("두 번째 통합 테스트도 첫 클래스와 같은 컨텍스트를 사용한다")
    void reusesFirstTestContext() {
        // when
        var other = new TestContextManager(IntegrationTestContextIntegrationTest.class)
                .getTestContext().getApplicationContext();

        // then
        assertThat(other).isSameAs(context);
    }
}
