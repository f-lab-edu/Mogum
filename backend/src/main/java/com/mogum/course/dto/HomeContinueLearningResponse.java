package com.mogum.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HomeContinueLearningResponse(CourseItem course) {
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CourseItem(long id, String title, LessonItem nextLesson) {
	}

	public record LessonItem(long id, String title) {
	}
}
