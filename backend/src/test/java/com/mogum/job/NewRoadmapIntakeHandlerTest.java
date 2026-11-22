package com.mogum.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mogum.job.domain.JobType;

class NewRoadmapIntakeHandlerTest {
	@Test
	@DisplayName("새 로드맵 받기는 추가 대상 검사 없이 허용한다")
	void acceptsWithoutTargetChecks() {
		// given
		var handler = new NewRoadmapIntakeHandler();

		// when & then
		assertThat(handler.type()).isEqualTo(JobType.NEW_ROADMAP);
		assertThatCode(() -> handler.check(1L, new JobRequest(JobType.NEW_ROADMAP, null, null, null)))
			.doesNotThrowAnyException();
	}
}
