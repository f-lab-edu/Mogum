package com.mogum.generation.plan;

import java.util.List;

import com.mogum.course.domain.PlannedConcept;

public record LessonOutline(String title, String description, List<PlannedConcept> concepts) {
	public LessonOutline {
		concepts = List.copyOf(concepts);
	}
}
