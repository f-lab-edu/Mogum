package com.mogum.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.mogum.card.service.ConceptLookupService;
import com.mogum.card.service.LessonCardWriter;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.ChatCall;
import com.mogum.generation.EmbeddingCall;
import com.mogum.generation.EmbeddingSettings;
import com.mogum.generation.MaterialGenerator;
import com.mogum.generation.MaterialReader;
import com.mogum.generation.NewCourseGenerator;
import com.mogum.generation.NewRoadmapGenerator;
import com.mogum.generation.PlanRoadmapCourseGenerator;
import com.mogum.generation.RelatedConceptPreparer;
import com.mogum.generation.ReplanCourseGenerator;
import com.mogum.generation.ReplanRoadmapGenerator;
import com.mogum.generation.RoadmapCoursePlanReader;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.repository.LlmCallRepository;
import com.mogum.generation.material.MaterialFormat;
import com.mogum.generation.plan.CoursePlanFormat;
import com.mogum.generation.plan.RoadmapPlanFormat;

import jakarta.persistence.EntityManager;

@Configuration(proxyBeanMethods = false)
public class GenerationConfiguration {
	@Bean
	public MaterialReader materialReader(LessonRepository lessonRepository,
		PlatformTransactionManager platformTransactionManager) {
		return new MaterialReader(lessonRepository, platformTransactionManager);
	}

	@Bean
	public MaterialGenerator materialGenerator(ChatCall chatCall, EmbeddingCall embeddingCall,
		LlmProperties llmProperties,
		MaterialReader materialReader, LessonRepository lessonRepository, MaterialRepository materialRepository,
		LessonCardWriter lessonCardWriter, ConceptLookupService conceptLookupService, Clock clock) {
		return new MaterialGenerator(chatCall, llmProperties.material(), embeddingCall,
			new EmbeddingSettings(llmProperties.embedding().model(), llmProperties.embedding().timeout()),
			new MaterialFormat(), materialReader, lessonRepository, materialRepository, lessonCardWriter,
			conceptLookupService, clock);
	}

	@Bean
	public RelatedConceptPreparer relatedConceptPreparer(ConceptLookupService conceptLookupService,
		EmbeddingCall embeddingCall, LlmProperties llmProperties, Clock clock) {
		return new RelatedConceptPreparer(conceptLookupService, embeddingCall,
			new EmbeddingSettings(llmProperties.embedding().model(), llmProperties.embedding().timeout()), clock);
	}

	@Bean
	public NewCourseGenerator newCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
		CourseRepository courseRepository, LessonRepository lessonRepository,
		RelatedConceptPreparer relatedConceptPreparer) {
		return new NewCourseGenerator(chatCall, llmProperties.planning(), new CoursePlanFormat(),
			relatedConceptPreparer, courseRepository, lessonRepository);
	}

	@Bean
	public NewRoadmapGenerator newRoadmapGenerator(ChatCall chatCall, LlmProperties llmProperties,
		CourseRepository courseRepository, RoadmapRepository roadmapRepository,
		RelatedConceptPreparer relatedConceptPreparer) {
		return new NewRoadmapGenerator(chatCall, llmProperties.planning(), new RoadmapPlanFormat(),
			relatedConceptPreparer, courseRepository, roadmapRepository);
	}

	@Bean
	public RoadmapCoursePlanReader roadmapCoursePlanReader(CourseRepository courseRepository,
		LessonRepository lessonRepository, PlatformTransactionManager platformTransactionManager) {
		return new RoadmapCoursePlanReader(courseRepository, lessonRepository, platformTransactionManager);
	}

	@Bean
	public PlanRoadmapCourseGenerator planRoadmapCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
		RoadmapCoursePlanReader roadmapCoursePlanReader, CourseRepository courseRepository,
		LessonRepository lessonRepository, RelatedConceptPreparer relatedConceptPreparer) {
		return new PlanRoadmapCourseGenerator(chatCall, llmProperties.planning(), new CoursePlanFormat(),
			relatedConceptPreparer, roadmapCoursePlanReader, courseRepository, lessonRepository);
	}

	@Bean
	public ReplanCourseGenerator replanCourseGenerator(ChatCall chatCall, LlmProperties llmProperties,
		RoadmapCoursePlanReader roadmapCoursePlanReader, CourseRepository courseRepository,
		LessonRepository lessonRepository, EntityManager entityManager,
		PlatformTransactionManager platformTransactionManager, RelatedConceptPreparer relatedConceptPreparer) {
		return new ReplanCourseGenerator(chatCall, llmProperties.planning(), new CoursePlanFormat(),
			relatedConceptPreparer, roadmapCoursePlanReader, courseRepository, lessonRepository, entityManager,
			platformTransactionManager);
	}

	@Bean
	public ReplanRoadmapGenerator replanRoadmapGenerator(ChatCall chatCall, LlmProperties llmProperties,
		CourseRepository courseRepository, RoadmapRepository roadmapRepository, EntityManager entityManager,
		PlatformTransactionManager platformTransactionManager, RelatedConceptPreparer relatedConceptPreparer) {
		return new ReplanRoadmapGenerator(chatCall, llmProperties.planning(), new RoadmapPlanFormat(),
			relatedConceptPreparer, courseRepository, roadmapRepository, entityManager, platformTransactionManager);
	}

	@Bean
	public LlmCallWriter llmCallWriter(EntityManager entityManager, LlmCallRepository llmCallRepository,
		PlatformTransactionManager platformTransactionManager) {
		return new LlmCallWriter(entityManager, llmCallRepository, platformTransactionManager);
	}
}
