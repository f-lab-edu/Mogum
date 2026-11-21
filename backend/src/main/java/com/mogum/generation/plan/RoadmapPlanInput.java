package com.mogum.generation.plan;

public sealed interface RoadmapPlanInput {
	PlanningInput input();

	record NewRoadmap(PlanningInput input) implements RoadmapPlanInput {
	}

	record ReplanRoadmap(PlanningInput input, RoadmapPlan previousPlan, String feedback) implements RoadmapPlanInput {
	}
}
