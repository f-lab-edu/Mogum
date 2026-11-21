package com.mogum.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GenerationFailuresTest {
    @Test
    @DisplayName("예상 밖 예외는 실패로 돌리고 타입과 위치만 ERROR로 한 번 남긴다")
    void logsOnlyTypeAndStackLocation(CapturedOutput output) {
        // given
        var exception = new IllegalStateException("LEAKCHECK-message",
                new IllegalArgumentException("LEAKCHECK-cause"));
        exception.addSuppressed(new UnsupportedOperationException("LEAKCHECK-suppressed"));

        // when
        var outcome = GenerationFailures.unavailable(exception);

        // then
        assertThat(outcome).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
        assertThat(output.getAll().lines().filter(line -> line.contains("job generate failed")))
                .singleElement()
                .asString()
                .contains("ERROR");
        assertThat(output.getAll())
                .contains("java.lang.IllegalStateException", "at com.mogum.generation.GenerationFailuresTest")
                .doesNotContain("LEAKCHECK", "Caused by:", "Suppressed:",
                        "java.lang.IllegalArgumentException", "java.lang.UnsupportedOperationException");
    }
}
