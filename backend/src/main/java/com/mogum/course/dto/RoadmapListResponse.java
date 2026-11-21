package com.mogum.course.dto;

import com.mogum.course.domain.RoadmapStatus;

import java.util.List;

public record RoadmapListResponse(List<Item> roadmaps) {
    public record Item(long id, String title, String description, RoadmapStatus status,
            int completedCourses, int totalCourses) { }
}
