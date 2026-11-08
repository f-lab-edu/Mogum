package com.mogum.curriculum.repository;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.curriculum.domain.Curriculum;
import com.mogum.curriculum.domain.CurriculumFixtures;
import com.mogum.curriculum.domain.CurriculumGroup;
import com.mogum.curriculum.domain.CurriculumStep;
import com.mogum.curriculum.domain.Lesson;
import com.mogum.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class CurriculumOwnershipRepositoryTest {
    @Autowired private CurriculumGroupRepository groups;
    @Autowired private CurriculumStepRepository steps;
    @Autowired private CurriculumRepository curriculums;
    @Autowired private LessonRepository lessons;
    @Autowired private UserRepository users;
    private User owner;
    private CurriculumGroup group;
    private CurriculumStep step;
    private Curriculum curriculum;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        owner = users.save(UserFixtures.user("owner@example.com"));
        group = groups.save(CurriculumFixtures.group(owner.getId()));
        step = steps.save(CurriculumFixtures.step(group));
        curriculum = curriculums.save(CurriculumFixtures.curriculumForStep(step));
        lesson = lessons.save(CurriculumFixtures.lesson(curriculum));
    }

    @Test
    @DisplayName("내 독립 커리큘럼을 주인 ID로 조회한다")
    void findsOwnedSingleCurriculum() {
        // given
        var single = curriculums.save(CurriculumFixtures.curriculum(owner.getId(), "독립 주제", null, "독립 제목"));

        // when
        var found = curriculums.findByIdAndUserId(single.getId(), owner.getId()).orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(single.getId());
        assertThat(found.getUserId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("남의 독립 커리큘럼은 조회하지 못한다")
    void hidesOtherSingleCurriculum() {
        // given
        var single = curriculums.save(CurriculumFixtures.curriculum(owner.getId(), "독립 주제", null, "독립 제목"));
        var other = users.save(UserFixtures.user("other@example.com"));

        // when
        var found = curriculums.findByIdAndUserId(single.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("없는 커리큘럼과 남의 것은 같은 빈 결과를 반환한다")
    void hidesMissingCurriculum() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));
        var hidden = curriculums.findByIdAndUserId(curriculum.getId(), other.getId());

        // when
        var missing = curriculums.findByIdAndUserId(-1L, owner.getId());

        // then
        assertThat(missing).isEmpty().isEqualTo(hidden);
    }

    @Test
    @DisplayName("단계 커리큘럼도 묶음 주인 ID로 조회한다")
    void findsOwnedStepCurriculum() {
        // when
        var found = curriculums.findByIdAndUserId(curriculum.getId(), group.getUserId()).orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(curriculum.getId());
        assertThat(found.getUserId()).isEqualTo(group.getUserId());
        assertThat(found.getStep().getId()).isEqualTo(step.getId());
    }

    @Test
    @DisplayName("남의 단계 커리큘럼은 같은 조회 메서드로 걸러진다")
    void hidesOtherStepCurriculum() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));

        // when
        var found = curriculums.findByIdAndUserId(curriculum.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("내 묶음을 주인 ID로 조회한다")
    void findsOwnedGroup() {
        // when
        var found = groups.findByIdAndUserId(group.getId(), owner.getId()).orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(group.getId());
        assertThat(found.getUserId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("남의 묶음은 조회하지 못한다")
    void hidesOtherGroup() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));

        // when
        var found = groups.findByIdAndUserId(group.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("없는 묶음과 남의 것은 같은 빈 결과를 반환한다")
    void hidesMissingGroup() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));
        var hidden = groups.findByIdAndUserId(group.getId(), other.getId());

        // when
        var missing = groups.findByIdAndUserId(-1L, owner.getId());

        // then
        assertThat(missing).isEmpty().isEqualTo(hidden);
    }

    @Test
    @DisplayName("내 강의를 커리큘럼 주인 ID로 조회한다")
    void findsOwnedLesson() {
        // when
        var found = lessons.findByIdAndCurriculum_UserId(lesson.getId(), owner.getId()).orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(lesson.getId());
        assertThat(found.getCurriculum().getId()).isEqualTo(curriculum.getId());
    }

    @Test
    @DisplayName("남의 강의는 조회하지 못한다")
    void hidesOtherLesson() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));

        // when
        var found = lessons.findByIdAndCurriculum_UserId(lesson.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("없는 강의와 남의 것은 같은 빈 결과를 반환한다")
    void hidesMissingLesson() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));
        var hidden = lessons.findByIdAndCurriculum_UserId(lesson.getId(), other.getId());

        // when
        var missing = lessons.findByIdAndCurriculum_UserId(-1L, owner.getId());

        // then
        assertThat(missing).isEmpty().isEqualTo(hidden);
    }

    @Test
    @DisplayName("내 단계를 묶음 주인 ID로 조회한다")
    void findsOwnedStep() {
        // when
        var found = steps.findByIdAndGroup_UserId(step.getId(), owner.getId()).orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(step.getId());
        assertThat(found.getGroup().getId()).isEqualTo(group.getId());
    }

    @Test
    @DisplayName("남의 단계는 조회하지 못한다")
    void hidesOtherStep() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));

        // when
        var found = steps.findByIdAndGroup_UserId(step.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("없는 단계와 남의 것은 같은 빈 결과를 반환한다")
    void hidesMissingStep() {
        // given
        var other = users.save(UserFixtures.user("other@example.com"));
        var hidden = steps.findByIdAndGroup_UserId(step.getId(), other.getId());

        // when
        var missing = steps.findByIdAndGroup_UserId(-1L, owner.getId());

        // then
        assertThat(missing).isEmpty().isEqualTo(hidden);
    }
}
