package com.mogum.course.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.PlannedConcept;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourseResponse(long id, CourseStatus status, String title, Long roadmapId,
	List<LessonItem> lessons, Progress progress, Long nextLessonId, Instant completedAt, int todayReviewCount,
	MasteryReportCounts masteryReport) {
	public record LessonItem(long id, int position, String title, String description,
		List<PlannedConcept> concepts, boolean completed) {
	}

	public record MasteryReportCounts(int shortened, int revisited) {
	}

	public record Progress(int completedLessons, int totalLessons) {
	}
}
