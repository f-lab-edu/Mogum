package com.mogum.generation.call.domain;

import com.mogum.job.domain.JobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallTest {
    @Test
    @DisplayName("시작 팩토리는 두 연결과 호출 정보를 구별해 담고 결과와 사용량은 비운다")
    void startsWithMetadataAndNoResult() {
        // when
        var call = LlmCall.start(11L, 22L, JobType.REPLAN_COURSE, LlmCallType.CONTINUATION, "test-model");

        // then
        assertThat(call.getJobId()).isEqualTo(11L);
        assertThat(call.getUserId()).isEqualTo(22L);
        assertThat(call.getJobType()).isEqualTo(JobType.REPLAN_COURSE);
        assertThat(call.getCallType()).isEqualTo(LlmCallType.CONTINUATION);
        assertThat(call.getModel()).isEqualTo("test-model");
        assertThat(call.getOutcome()).isNull();
        assertThat(call.getDurationMs()).isNull();
        assertThat(call.getInputTokens()).isNull();
        assertThat(call.getOutputTokens()).isNull();
        assertThat(call.getCost()).isNull();
    }

    @Test
    @DisplayName("완료는 입력과 출력 토큰을 구별해 결과와 비용 및 걸린 시간을 채운다")
    void finishesWithResultAndUsage() {
        // given
        var call = LlmCallFixtures.started(null, null, JobType.MATERIAL, LlmCallType.CHAT, "test-model");

        // when
        call.finish(LlmCallOutcome.SUCCEEDED, 123, 456, new BigDecimal("0.00123456789"), 789);

        // then
        assertThat(call.getOutcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
        assertThat(call.getInputTokens()).isEqualTo(123);
        assertThat(call.getOutputTokens()).isEqualTo(456);
        assertThat(call.getCost()).isEqualByComparingTo("0.00123456789");
        assertThat(call.getDurationMs()).isEqualTo(789);
    }
}
