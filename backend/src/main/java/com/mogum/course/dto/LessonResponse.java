package com.mogum.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LessonResponse(long id, long courseId, String courseTitle, int position, String title,
	boolean completed, MaterialBody material, MaterialJob job) {
	public record MaterialBody(String body) {
	}
	public record MaterialJob(long id, String status) {
	}
}
