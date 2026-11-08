package com.mogum.curriculum.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.curriculum.domain.Curriculum;
import com.mogum.curriculum.domain.CurriculumFixtures;
import com.mogum.curriculum.domain.CurriculumGroup;
import com.mogum.curriculum.domain.CurriculumStep;
import com.mogum.curriculum.domain.Lesson;
import com.mogum.support.RepositoryTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class CurriculumLazyLoadingRepositoryTest {
    @Autowired private CurriculumGroupRepository groups;
    @Autowired private CurriculumStepRepository steps;
    @Autowired private CurriculumRepository curriculums;
    @Autowired private LessonRepository lessons;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    private TransactionTemplate transactions;
    private CurriculumGroup group;
    private CurriculumStep step;
    private Curriculum curriculum;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        var owner = users.save(UserFixtures.user("owner@example.com"));
        group = groups.save(CurriculumFixtures.group(owner.getId()));
        step = steps.save(CurriculumFixtures.step(group));
        curriculum = curriculums.save(CurriculumFixtures.curriculumForStep(step));
        lesson = lessons.save(CurriculumFixtures.lesson(curriculum));
    }

    @Test
    @DisplayName("단계의 묶음은 트랜잭션 안에서 접근할 때 초기화된다")
    void loadsStepGroupLazily() {
        // when
        var snapshot = transactions.execute(status -> {
            var reloaded = entityManager.find(CurriculumStep.class, step.getId());
            var parent = reloaded.getGroup();
            boolean initializedBeforeAccess = Hibernate.isInitialized(parent);
            return new LazySnapshot(initializedBeforeAccess, parent.getTitle());
        });

        // then
        assertThat(snapshot.initializedBeforeAccess()).isFalse();
        assertThat(snapshot.title()).isEqualTo(group.getTitle());
    }

    @Test
    @DisplayName("커리큘럼의 단계는 트랜잭션 안에서 접근할 때 초기화된다")
    void loadsCurriculumStepLazily() {
        // when
        var snapshot = transactions.execute(status -> {
            var reloaded = entityManager.find(Curriculum.class, curriculum.getId());
            var parent = reloaded.getStep();
            boolean initializedBeforeAccess = Hibernate.isInitialized(parent);
            return new LazySnapshot(initializedBeforeAccess, parent.getTitle());
        });

        // then
        assertThat(snapshot.initializedBeforeAccess()).isFalse();
        assertThat(snapshot.title()).isEqualTo(step.getTitle());
    }

    @Test
    @DisplayName("강의의 커리큘럼은 트랜잭션 안에서 접근할 때 초기화된다")
    void loadsLessonCurriculumLazily() {
        // when
        var snapshot = transactions.execute(status -> {
            var reloaded = entityManager.find(Lesson.class, lesson.getId());
            var parent = reloaded.getCurriculum();
            boolean initializedBeforeAccess = Hibernate.isInitialized(parent);
            return new LazySnapshot(initializedBeforeAccess, parent.getTitle());
        });

        // then
        assertThat(snapshot.initializedBeforeAccess()).isFalse();
        assertThat(snapshot.title()).isEqualTo(curriculum.getTitle());
    }

    private record LazySnapshot(boolean initializedBeforeAccess, String title) { }
}
