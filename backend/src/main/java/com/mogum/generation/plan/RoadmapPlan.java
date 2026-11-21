package com.mogum.generation.plan;

import java.util.List;

public record RoadmapPlan(String title, String description, List<CourseOutline> courses) {
	public RoadmapPlan {
		courses = List.copyOf(courses);
	}
}
