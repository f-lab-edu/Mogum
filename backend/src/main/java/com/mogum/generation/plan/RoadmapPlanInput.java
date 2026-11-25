package com.mogum.generation.plan;

import java.util.List;

import com.mogum.card.service.ConceptLookupService.RelatedConcept;

public sealed interface RoadmapPlanInput {
	PlanningInput input();

	List<RelatedConcept> relatedConcepts();

	default RoadmapPlanInput withRelatedConcepts(List<RelatedConcept> concepts) {
		return switch (this) {
			case NewRoadmap roadmap -> new NewRoadmap(roadmap.input(), concepts);
			case ReplanRoadmap roadmap -> new ReplanRoadmap(roadmap.input(), roadmap.previousPlan(),
				roadmap.feedback(), concepts);
		};
	}

	default List<String> queryTexts() {
		return List.of(PlanPromptSupport.topicPurposeQuery(input()));
	}

	record NewRoadmap(PlanningInput input, List<RelatedConcept> relatedConcepts) implements RoadmapPlanInput {
		public NewRoadmap {
			relatedConcepts = List.copyOf(relatedConcepts);
		}

		public NewRoadmap(PlanningInput input) {
			this(input, List.of());
		}
	}

	record ReplanRoadmap(PlanningInput input, RoadmapPlan previousPlan, String feedback,
		List<RelatedConcept> relatedConcepts) implements RoadmapPlanInput {
		public ReplanRoadmap {
			relatedConcepts = List.copyOf(relatedConcepts);
		}

		public ReplanRoadmap(PlanningInput input, RoadmapPlan previousPlan, String feedback) {
			this(input, previousPlan, feedback, List.of());
		}
	}
}
