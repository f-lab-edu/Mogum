package com.mogum.generation.plan;

import java.util.List;

import com.mogum.card.service.ConceptLookupService.RelatedConcept;

public sealed interface CoursePlanInput {
	PlanningInput input();

	List<RelatedConcept> relatedConcepts();

	default CoursePlanInput withRelatedConcepts(List<RelatedConcept> concepts) {
		return switch (this) {
			case NewCourse course -> new NewCourse(course.input(), concepts);
			case RoadmapCourse course -> new RoadmapCourse(course.input(), course.context(), concepts);
			case ReplanCourse course -> new ReplanCourse(course.input(), course.context(), course.previousLessons(),
				course.feedback(), concepts);
		};
	}

	default List<String> queryTexts() {
		RoadmapContext context = switch (this) {
			case NewCourse ignored -> null;
			case RoadmapCourse course -> course.context();
			case ReplanCourse course -> course.context();
		};
		return context == null
			? List.of(PlanPromptSupport.topicPurposeQuery(input()))
			: context.coursePlan().concepts().stream().map(concept -> concept.definition()).toList();
	}

	record NewCourse(PlanningInput input, List<RelatedConcept> relatedConcepts) implements CoursePlanInput {
		public NewCourse {
			relatedConcepts = List.copyOf(relatedConcepts);
		}

		public NewCourse(PlanningInput input) {
			this(input, List.of());
		}
	}

	record RoadmapCourse(PlanningInput input, RoadmapContext context, List<RelatedConcept> relatedConcepts)
		implements
			CoursePlanInput {
		public RoadmapCourse {
			relatedConcepts = List.copyOf(relatedConcepts);
		}

		public RoadmapCourse(PlanningInput input, RoadmapContext context) {
			this(input, context, List.of());
		}
	}

	/** context가 없으면 단일 코스다. 피드백이 없어도 이전 목록은 넣는다. */
	record ReplanCourse(PlanningInput input, RoadmapContext context, List<LessonOutline> previousLessons,
		String feedback, List<RelatedConcept> relatedConcepts) implements CoursePlanInput {
		public ReplanCourse {
			previousLessons = List.copyOf(previousLessons);
			relatedConcepts = List.copyOf(relatedConcepts);
		}

		public ReplanCourse(PlanningInput input, RoadmapContext context, List<LessonOutline> previousLessons,
			String feedback) {
			this(input, context, previousLessons, feedback, List.of());
		}
	}

	/** 앞 코스 계획 없이, 바로 앞 코스 한 개의 실제 목록과 뒤 코스들의 계획만 받는다. */
	record RoadmapContext(int position, CourseOutline coursePlan, List<LessonOutline> previousCourseLessons,
		List<CourseOutline> followingCoursePlans) {
		public RoadmapContext {
			if (position < 1) {
				throw new IllegalArgumentException("Course position must be positive");
			}
			previousCourseLessons = List.copyOf(previousCourseLessons);
			followingCoursePlans = List.copyOf(followingCoursePlans);
		}
	}
}
