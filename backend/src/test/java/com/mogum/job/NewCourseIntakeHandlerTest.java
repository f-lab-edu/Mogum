package com.mogum.job;

import com.mogum.job.domain.JobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NewCourseIntakeHandlerTest {
    @Test
    @DisplayName("새 코스 받기는 추가 대상 검사 없이 허용한다")
    void acceptsWithoutTargetChecks() {
        // given
        var handler = new NewCourseIntakeHandler();

        // when & then
        assertThat(handler.type()).isEqualTo(JobType.NEW_COURSE);
        assertThatCode(() -> handler.check(1L, new JobRequest(JobType.NEW_COURSE, null, null, null)))
                .doesNotThrowAnyException();
    }
}
