package com.mogum.course.dto;

import com.mogum.course.domain.CourseStatus;

import java.util.List;

public record CourseListResponse(List<Item> courses) {
    public record Item(long id, String title, CourseStatus status) { }
}
