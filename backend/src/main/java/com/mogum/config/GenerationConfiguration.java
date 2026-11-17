package com.mogum.config;

import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.ChatCall;
import com.mogum.generation.NewCourseGenerator;
import com.mogum.generation.NewRoadmapGenerator;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.repository.LlmCallRepository;
import com.mogum.generation.plan.CoursePlanFormat;
import com.mogum.generation.plan.RoadmapPlanFormat;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class GenerationConfiguration {
    @Bean
    public NewCourseGenerator newCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
            CourseRepository courseRepository, LessonRepository lessonRepository) {
        return new NewCourseGenerator(chatCall, llmProperties.chats().get("planning"), new CoursePlanFormat(),
                courseRepository, lessonRepository);
    }

    @Bean
    public NewRoadmapGenerator newRoadmapGenerator(ChatCall chatCall, LlmProperties llmProperties,
            CourseRepository courseRepository, RoadmapRepository roadmapRepository) {
        return new NewRoadmapGenerator(chatCall, llmProperties.chats().get("planning"), new RoadmapPlanFormat(),
                courseRepository, roadmapRepository);
    }

    @Bean
    public LlmCallWriter llmCallWriter(EntityManager entityManager, LlmCallRepository llmCallRepository,
            PlatformTransactionManager platformTransactionManager) {
        return new LlmCallWriter(entityManager, llmCallRepository, platformTransactionManager);
    }
}
