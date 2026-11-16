package com.mogum.job.controller;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.domain.JobType;
import com.mogum.support.NoJobHandlersIntegrationTest;
import com.mogum.support.TestRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@NoJobHandlersIntegrationTest
class JobControllerNoHandlersIntegrationTest {
    @Autowired TestRequests requests;
    @Autowired UserRepository users;
    @Autowired CreditEntryRepository entries;
    @Autowired CourseRepository courses;
    @Autowired RoadmapRepository roadmaps;
    @Autowired LessonRepository lessons;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @ParameterizedTest(name = "종류={0}")
    @EnumSource(JobType.class)
    @DisplayName("처리기가 없는 종류는 HTTP 받기를 거절하고 작업과 크레딧을 보존한다")
    void rejectsTypesWithoutHandlers(JobType type) {
        // given
        var user = users.save(UserFixtures.approved("me@example.com"));
        entries.save(CreditEntry.refill(user, 100));
        var course = courses.save(CourseFixtures.single(user.getId()));
        var roadmap = roadmaps.save(RoadmapFixtures.roadmap(user.getId()));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        Map<String, Object> body = switch (type) {
            case NEW_COURSE, NEW_ROADMAP -> Map.of("type", type, "topic", "자바", "level", "BEGINNER", "purpose", "WORK");
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> Map.of("type", type, "courseId", course.getId());
            case REPLAN_ROADMAP -> Map.of("type", type, "roadmapId", roadmap.getId());
            case MATERIAL -> Map.of("type", type, "lessonId", lesson.getId());
        };
        var beforeUser = jdbc.queryForMap("SELECT * FROM users");
        var beforeEntries = jdbc.queryForList("SELECT * FROM credit_entries");
        var client = requests.asUser(user.getId());

        // when
        var result = client.post().uri("/api/jobs").body(body).exchange();

        // then
        assertThat(context.getBeansOfType(JobIntakeHandler.class)).isEmpty();
        result.expectStatus().isBadRequest().expectBody().json("""
                {"code":"INVALID_REQUEST","message":"요청을 확인할 수 없습니다"}
                """, JsonCompareMode.STRICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM users")).isEqualTo(beforeUser);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(beforeEntries);
    }
}
