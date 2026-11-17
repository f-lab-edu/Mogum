package com.mogum.config;

import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.ChatCall;
import com.mogum.generation.ChatSettings;
import com.mogum.generation.NewCourseGenerator;
import com.mogum.generation.NewRoadmapGenerator;
import com.mogum.generation.PlanRoadmapCourseGenerator;
import com.mogum.generation.ReplanCourseGenerator;
import com.mogum.generation.ReplanRoadmapGenerator;
import com.mogum.generation.RoadmapCoursePlanReader;
import com.mogum.generation.TokenPrices;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.repository.LlmCallRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationConfigurationTest {
    @Test
    @DisplayName("config는 호출 기록과 새 코스 및 로드맵 생성 빈을 조립한다")
    void wiresCallWriter() {
        // given
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(GenerationConfiguration.class)
                .withBean(EntityManager.class, () -> mock(EntityManager.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(LlmCallRepository.class, () -> mock(LlmCallRepository.class))
                .withBean(ChatCall.class, () -> mock(ChatCall.class))
                .withBean(CourseRepository.class, () -> mock(CourseRepository.class))
                .withBean(RoadmapRepository.class, () -> mock(RoadmapRepository.class))
                .withBean(LessonRepository.class, () -> mock(LessonRepository.class))
                .withBean(LlmProperties.class, () -> new LlmProperties(new LlmProperties.Openai("fake-key", ""),
                        Map.of("planning", new ChatSettings("test-model", 4000, Duration.ofSeconds(50), 1)),
                        Map.of("test-model", new TokenPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)),
                        Duration.ofSeconds(10)));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed().hasSingleBean(LlmCallWriter.class)
                    .hasSingleBean(NewCourseGenerator.class).hasSingleBean(NewRoadmapGenerator.class)
                    .hasSingleBean(ReplanCourseGenerator.class).hasSingleBean(ReplanRoadmapGenerator.class)
                    .hasSingleBean(PlanRoadmapCourseGenerator.class).hasSingleBean(RoadmapCoursePlanReader.class);
        });
    }
}
