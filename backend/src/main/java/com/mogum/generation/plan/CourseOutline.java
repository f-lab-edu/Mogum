package com.mogum.generation.plan;

import com.mogum.course.domain.PlannedConcept;

import java.util.List;

public record CourseOutline(String title, String description, List<PlannedConcept> concepts) {
    public CourseOutline {
        concepts = List.copyOf(concepts);
    }
}
