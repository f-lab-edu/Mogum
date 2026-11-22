package com.mogum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.ChatCall;
import com.mogum.generation.NewCourseGenerator;
import com.mogum.generation.NewRoadmapGenerator;
import com.mogum.generation.PlanRoadmapCourseGenerator;
import com.mogum.generation.ReplanCourseGenerator;
import com.mogum.generation.ReplanRoadmapGenerator;
import com.mogum.generation.RoadmapCoursePlanReader;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.repository.LlmCallRepository;
import com.mogum.generation.plan.CoursePlanFormat;
import com.mogum.generation.plan.RoadmapPlanFormat;

import jakarta.persistence.EntityManager;

@Configuration(proxyBeanMethods = false)
public class GenerationConfiguration {
	@Bean
	public NewCourseGenerator newCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
		CourseRepository courseRepository, LessonRepository lessonRepository) {
		return new NewCourseGenerator(chatCall, llmProperties.planning(), new CoursePlanFormat(),
			courseRepository, lessonRepository);
	}

	@Bean
	public NewRoadmapGenerator newRoadmapGenerator(ChatCall chatCall, LlmProperties llmProperties,
		CourseRepository courseRepository, RoadmapRepository roadmapRepository) {
		return new NewRoadmapGenerator(chatCall, llmProperties.planning(), new RoadmapPlanFormat(),
			courseRepository, roadmapRepository);
	}

	@Bean
	public RoadmapCoursePlanReader roadmapCoursePlanReader(CourseRepository courseRepository,
		LessonRepository lessonRepository, PlatformTransactionManager platformTransactionManager) {
		return new RoadmapCoursePlanReader(courseRepository, lessonRepository, platformTransactionManager);
	}

	@Bean
	public PlanRoadmapCourseGenerator planRoadmapCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
		RoadmapCoursePlanReader roadmapCoursePlanReader, CourseRepository courseRepository,
		LessonRepository lessonRepository) {
		return new PlanRoadmapCourseGenerator(chatCall, llmProperties.planning(), new CoursePlanFormat(),
			roadmapCoursePlanReader, courseRepository, lessonRepository);
	}

	@Bean
	public ReplanCourseGenerator replanCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
		RoadmapCoursePlanReader roadmapCoursePlanReader, CourseRepository courseRepository,
		LessonRepository lessonRepository, EntityManager entityManager,
		PlatformTransactionManager platformTransactionManager) {
		return new ReplanCourseGenerator(chatCall, llmProperties.planning(), new CoursePlanFormat(),
			roadmapCoursePlanReader, courseRepository, lessonRepository, entityManager, platformTransactionManager);
	}

	@Bean
	public ReplanRoadmapGenerator replanRoadmapGenerator(ChatCall chatCall, LlmProperties llmProperties,
		CourseRepository courseRepository, RoadmapRepository roadmapRepository, EntityManager entityManager,
		PlatformTransactionManager platformTransactionManager) {
		return new ReplanRoadmapGenerator(chatCall, llmProperties.planning(), new RoadmapPlanFormat(),
			courseRepository, roadmapRepository, entityManager, platformTransactionManager);
	}

	@Bean
	public LlmCallWriter llmCallWriter(EntityManager entityManager, LlmCallRepository llmCallRepository,
		PlatformTransactionManager platformTransactionManager) {
		return new LlmCallWriter(entityManager, llmCallRepository, platformTransactionManager);
	}
}
