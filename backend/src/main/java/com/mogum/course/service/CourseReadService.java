package com.mogum.course.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.service.TodayReviewService;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Lesson;
import com.mogum.course.dto.CourseListResponse;
import com.mogum.course.dto.CourseResponse;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CourseReadService {
	private final CourseRepository courseRepository;
	private final LessonRepository lessonRepository;
	private final TodayReviewService todayReviewService;

	public CourseResponse read(long userId, long courseId) {
		var course = courseRepository.findById(courseId)
			.filter(value -> value.getUserId() == userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		var listed = lessonRepository.findByCourseIdOrderByPositionAsc(courseId);
		var lessons = listed.stream()
			.map(lesson -> new CourseResponse.LessonItem(lesson.getId(), lesson.getPosition(), lesson.getTitle(),
				lesson.getDescription(), lesson.getConcepts(), lesson.getCompletedAt() != null))
			.toList();
		int completed = (int)lessons.stream().filter(CourseResponse.LessonItem::completed).count();
		Long nextLessonId = nextLesson(course, listed).map(Lesson::getId).orElse(null);

		var report = course.getMasteryReport();
		var masteryReport = course.getStatus() == CourseStatus.REVIEWING && report != null
			? new CourseResponse.MasteryReportCounts(report.shortened().size(), report.revisited().size())
			: null;

		return new CourseResponse(courseId, course.getStatus(), course.getTitle(),
			course.getRoadmap() == null ? null : course.getRoadmap().getId(), lessons,
			new CourseResponse.Progress(completed, lessons.size()), nextLessonId,
			course.getStatus() == CourseStatus.COMPLETED ? course.getCompletedAt() : null,
			todayReviewService.count(userId, courseId), masteryReport);
	}

	static Optional<Lesson> nextLesson(Course course, List<Lesson> lessons) {
		return course.getStatus() == CourseStatus.LEARNING
			? lessons.stream().filter(lesson -> lesson.getCompletedAt() == null).findFirst()
			: Optional.empty();
	}

	public CourseListResponse list(long userId) {
		return new CourseListResponse(courseRepository
			.findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, CourseStatus.NOT_PLANNED)
			.stream()
			.map(course -> new CourseListResponse.Item(course.getId(), course.getTitle(), course.getStatus()))
			.toList());
	}
}
