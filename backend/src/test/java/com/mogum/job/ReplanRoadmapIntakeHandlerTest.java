package com.mogum.job;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.FakeRoadmapRepository;
import com.mogum.job.domain.JobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplanRoadmapIntakeHandlerTest {
    private final FakeRoadmapRepository roadmapRepository = new FakeRoadmapRepository();
    private final ReplanRoadmapIntakeHandler replanRoadmapIntakeHandler =
            new ReplanRoadmapIntakeHandler(roadmapRepository);

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(RoadmapStatus.class)
    @DisplayName("다시 짜기는 검토 중에만 받고 상태를 바꾸지 않는다")
    void acceptsOnlyReviewing(RoadmapStatus status) {
        // given
        var target = roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(1L), status));
        var request = new JobRequest(JobType.REPLAN_ROADMAP, target.getId(), null, "피드백");

        // when & then
        if (status == RoadmapStatus.REVIEWING) {
            replanRoadmapIntakeHandler.check(1L, request);
            assertThat(replanRoadmapIntakeHandler.type()).isEqualTo(JobType.REPLAN_ROADMAP);
        } else {
            assertThatThrownBy(() -> replanRoadmapIntakeHandler.check(1L, request))
                    .isInstanceOfSatisfying(ApiException.class, e ->
                            assertThat(e.errorCode()).isEqualTo(ErrorCode.REPLAN_NOT_ALLOWED));
        }
        assertThat(target.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("없는 대상은 없는 것으로 답한다")
    void rejectsMissingTarget() {
        // given
        var request = new JobRequest(JobType.REPLAN_ROADMAP, 999L, null, null);

        // when & then
        assertThatThrownBy(() -> replanRoadmapIntakeHandler.check(1L, request))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
