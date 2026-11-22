package com.mogum.generation.plan;

import java.util.List;

import com.mogum.course.domain.PlannedConcept;

public record CourseOutline(String title, String description, List<PlannedConcept> concepts) {
	public CourseOutline {
		concepts = List.copyOf(concepts);
	}
}
