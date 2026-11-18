package com.mogum.course.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class RoadmapRepositoryTest {
    @Autowired private UserRepository userRepository;
    @Autowired private RoadmapRepository roadmapRepository;
    @Autowired private TestClock testClock;

    @ParameterizedTest(name = "수준 {0}, 목적 {2}, 메모 {4}")
    @CsvSource({"BEGINNER,,WORK,,자바 예제", "BASICS,,INTERVIEW,,", "FAMILIAR,,GENERAL,,",
            "OTHER,실습 경험,OTHER,개인 프로젝트,"})
    @DisplayName("로드맵의 입력과 생성 내용 및 공용 시계의 시각을 다시 읽는다")
    void reloadsRoadmapWithInputAndAuditing(
            Level level, String levelOther, Purpose purpose,
            String purposeOther, String memo
    ) {
        // given
        var user = userRepository.save(UserFixtures.user("me@example.com"));
        var instant = Instant.parse("2026-11-10T01:23:45.123456Z");
        testClock.set(instant);

        var input = new LearningInput("서버 개발", level, levelOther, purpose, purposeOther, memo);
        var saved = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId(), input, "서버 로드맵", "서버를 만들 수 있어요"));

        // when
        var actual = roadmapRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getUserId()).isEqualTo(user.getId());
        assertThat(actual.getTitle()).isEqualTo("서버 로드맵");
        assertThat(actual.getDescription()).isEqualTo("서버를 만들 수 있어요");
        assertThat(actual.getStatus()).isEqualTo(RoadmapStatus.REVIEWING);
        assertThat(actual.getInput()).usingRecursiveComparison().isEqualTo(input);

        assertThat(actual.getCreatedAt()).isEqualTo(instant);
        assertThat(actual.getUpdatedAt()).isEqualTo(instant);
    }
}

