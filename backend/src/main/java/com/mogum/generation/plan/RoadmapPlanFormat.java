package com.mogum.generation.plan;

import java.util.ArrayList;
import java.util.Optional;

public final class RoadmapPlanFormat {
	public PlanPrompt prompt(RoadmapPlanInput input) {
		var data = PlanPromptSupport.materials(input.input(), true);
		if (input instanceof RoadmapPlanInput.ReplanRoadmap replan) {
			data.put("previousPlan", replan.previousPlan());
			PlanPromptSupport.optionalText(data, "feedback", replan.feedback());
		}
		return PlanPromptSupport.prompt("roadmap.txt", null, data);
	}

	/** ChatCall.call의 해석 함수로 this::parse를 넘긴다. */
	public Optional<RoadmapPlan> parse(String text) {
		return PlanJson.read(text).flatMap(root -> {
			if (!PlanJson.title(root.path("title")) || !PlanJson.text(root.path("description"))) {
				return Optional.empty();
			}
			var courses = root.path("courses");
			if (!courses.isArray() || courses.size() < 2)
				return Optional.empty();

			var result = new ArrayList<CourseOutline>();
			for (var course : courses) {
				if (!PlanJson.outline(course, true))
					return Optional.empty();
				result.add(new CourseOutline(course.path("title").asString(), course.path("description").asString(),
					PlanJson.concepts(course)));
			}
			return Optional.of(new RoadmapPlan(root.path("title").asString(), root.path("description").asString(),
				result));
		});
	}
}
