package com.mogum.generation.plan;

import com.mogum.course.domain.PlannedConcept;

import java.util.List;

public record LessonOutline(String title, String description, List<PlannedConcept> concepts) {
    public LessonOutline {
        concepts = List.copyOf(concepts);
    }
}
