package com.mogum.worker;

import com.mogum.config.WorkerProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;
import static org.mockito.BDDMockito.willAnswer;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WorkerLifecycleTest {
    @Mock JobRunner jobRunner;

    @Test
    @DisplayName("연속 예외에도 루프는 살아 있고 스택은 첫 실패에만 남기며 성공 뒤 다시 허용한다")
    void survivesFailuresAndSuppressesRepeatedStacks(CapturedOutput output) {
        // given — 꺼내기를 계속 호출하는 순서 자체가 규칙이다.
        var attempts = new AtomicInteger();
        var failure = new IllegalStateException("LEAKCHECK-message", new RuntimeException("LEAKCHECK-cause"));
        failure.addSuppressed(new RuntimeException("LEAKCHECK-suppressed"));
        given(jobRunner.runOnce()).willAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 3 || attempt == 5) throw failure;
            return attempt == 4;
        });
        var lifecycle = new WorkerLifecycle(settings(1, Duration.ofMillis(10)), scheduler -> jobRunner);

        // when
        lifecycle.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> attempts.get() >= 6);
        } finally {
            lifecycle.stop();
        }

        // then
        assertThat(output.getAll().lines().filter(line -> line.contains("worker poll failed"))).hasSize(4);
        assertThat(output.getAll().lines().filter(line -> line.contains("RuntimeException: java.lang.IllegalStateException")))
                .hasSize(2);
        assertThat(output.getAll()).doesNotContain("LEAKCHECK");
        then(jobRunner).should().stopClaiming();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("빈손인 워커는 설정 간격을 기다리고 종료는 긴 쉬기를 즉시 깨운다")
    void wakesIdleWorkerOnStop() {
        // given
        var attempts = new AtomicInteger();
        given(jobRunner.runOnce()).willAnswer(invocation -> { attempts.incrementAndGet(); return false; });
        var lifecycle = new WorkerLifecycle(settings(1, Duration.ofSeconds(30)), scheduler -> jobRunner);
        lifecycle.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> attempts.get() == 1);

        // when
        long began = System.nanoTime();
        lifecycle.stop();

        // then
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(1));
        then(jobRunner).should(times(1)).runOnce();
    }

    @Test
    @DisplayName("워커 수 0이면 실행기와 스레드를 만들지 않는다")
    void createsNothingWhenDisabled() {
        // given
        var factories = new AtomicInteger();
        var lifecycle = new WorkerLifecycle(settings(0, Duration.ofMillis(10)), scheduler -> {
            factories.incrementAndGet();
            return jobRunner;
        });
        // when
        lifecycle.start();
        lifecycle.stop();
        // then
        assertThat(factories).hasValue(0);
        then(jobRunner).shouldHaveNoInteractions();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Spring의 실제 컨텍스트 종료는 워커 반환을 마친 뒤 Hikari 풀을 닫는다")
    void stopsBeforeConnectionPoolDestruction() throws Exception {
        // given
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var releasedWithOpenPool = new AtomicBoolean();
        var pool = new HikariDataSource();
        given(jobRunner.runOnce()).willAnswer(invocation -> {
            entered.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return true;
        });
        willAnswer(invocation -> { releasedWithOpenPool.set(!pool.isClosed()); return null; })
                .given(jobRunner).releaseCurrent();
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", HikariDataSource.class, () -> pool, definition -> definition.setDestroyMethodName("close"));
        context.registerBean(WorkerLifecycle.class, () -> new WorkerLifecycle(
                new WorkerProperties(Duration.ofMinutes(5), Duration.ofSeconds(30), 2, Duration.ofSeconds(1),
                        1, Duration.ofMillis(10), Duration.ofMillis(50)), scheduler -> jobRunner));
        context.refresh();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        try {
            context.close();
        } finally {
            finish.countDown();
        }

        // then
        assertThat(releasedWithOpenPool).isTrue();
        assertThat(pool.isClosed()).isTrue();
        then(jobRunner).should().stopClaiming();
        then(jobRunner).should().releaseCurrent();
    }

    private WorkerProperties settings(int count, Duration poll) {
        return new WorkerProperties(Duration.ofMinutes(5), Duration.ofSeconds(30), 2, Duration.ofSeconds(1),
                count, poll, Duration.ofSeconds(1));
    }
}
