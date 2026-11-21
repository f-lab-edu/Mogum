package com.mogum.course.dto;

import java.util.List;

import com.mogum.course.domain.CourseStatus;

public record CourseListResponse(List<Item> courses) {
	public record Item(long id, String title, CourseStatus status) {
	}
}
