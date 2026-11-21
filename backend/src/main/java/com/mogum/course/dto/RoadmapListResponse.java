package com.mogum.course.dto;

import java.util.List;

import com.mogum.course.domain.RoadmapStatus;

public record RoadmapListResponse(List<Item> roadmaps) {
	public record Item(long id, String title, String description, RoadmapStatus status,
		int completedCourses, int totalCourses) {
	}
}
