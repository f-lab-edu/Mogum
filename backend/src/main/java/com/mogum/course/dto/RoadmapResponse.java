package com.mogum.course.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.RoadmapStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadmapResponse(long id, RoadmapStatus status, String title, String description,
	List<CourseItem> courses, NextCourse nextCourse) {
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CourseItem(long id, int position, String title, String description, List<PlannedConcept> concepts,
		CourseStatus status, CourseProgress progress, Long jobId) {
	}

	public record NextCourse(long courseId, boolean enabled) {
	}
}
