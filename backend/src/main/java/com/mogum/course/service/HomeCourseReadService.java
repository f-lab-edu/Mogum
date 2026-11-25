package com.mogum.course.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.dto.HomeContinueLearningResponse;
import com.mogum.course.dto.HomeTodoResponse;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HomeCourseReadService {
	private final CourseRepository courseRepository;
	private final LessonRepository lessonRepository;
	private final RoadmapRepository roadmapRepository;
	private final RoadmapReadService roadmapReadService;

	public HomeContinueLearningResponse continueLearning(long userId) {
		var learning = courseRepository
			.findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, CourseStatus.NOT_PLANNED)
			.stream().filter(course -> course.getStatus() == CourseStatus.LEARNING).toList();
		boolean opened = learning.stream().anyMatch(course -> course.getLastLessonOpenedAt() != null);
		Comparator<Course> latest = Comparator.comparing(
			course -> opened ? course.getLastLessonOpenedAt() : course.getStartedAt(),
			Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));
		var selected = learning.stream().max(latest.thenComparing(Course::getId));
		return new HomeContinueLearningResponse(selected.map(course -> {
			var next = CourseReadService.nextLesson(course,
				lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()))
				.map(lesson -> new HomeContinueLearningResponse.LessonItem(lesson.getId(), lesson.getTitle()))
				.orElse(null);
			return new HomeContinueLearningResponse.CourseItem(course.getId(), course.getTitle(), next);
		}).orElse(null));
	}

	public HomeTodoResponse todos(long userId) {
		var entries = new ArrayList<TodoEntry>();
		for (var course : courseRepository
			.findByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, CourseStatus.NOT_PLANNED)) {
			if (course.getStatus() == CourseStatus.REVIEWING) {
				entries.add(new TodoEntry(course.getCreatedAt(), new HomeTodoResponse.Item(
					HomeTodoResponse.Type.COURSE_REVIEW, course.getId(), course.getTitle())));
			}
		}
		for (var roadmap : roadmapRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
			HomeTodoResponse.Type type;
			if (roadmap.getStatus() == RoadmapStatus.REVIEWING) {
				type = HomeTodoResponse.Type.ROADMAP_REVIEW;
			} else if (roadmap.getStatus() == RoadmapStatus.IN_PROGRESS) {
				var next = roadmapReadService.read(userId, roadmap.getId()).nextCourse();
				if (next == null || !next.enabled()) {
					continue;
				}
				type = HomeTodoResponse.Type.ROADMAP_NEXT_COURSE;
			} else {
				continue;
			}
			entries.add(new TodoEntry(roadmap.getCreatedAt(),
				new HomeTodoResponse.Item(type, roadmap.getId(), roadmap.getTitle())));
		}
		entries.sort(Comparator.comparing(TodoEntry::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(entry -> entry.item().targetId(), Comparator.reverseOrder())
			.thenComparing(entry -> entry.item().type().name()));
		return new HomeTodoResponse(entries.stream().map(TodoEntry::item).toList());
	}

	private record TodoEntry(Instant createdAt, HomeTodoResponse.Item item) {
	}
}
