package com.mogum.course.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.dto.CourseProgress;
import com.mogum.course.dto.RoadmapListResponse;
import com.mogum.course.dto.RoadmapResponse;
import com.mogum.course.repository.CourseJobRepository;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.RoadmapRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoadmapReadService {
	private final RoadmapRepository roadmapRepository;
	private final CourseRepository courseRepository;
	private final CourseJobRepository courseJobRepository;

	public RoadmapResponse read(long userId, long roadmapId) {
		var roadmap = roadmapRepository.findById(roadmapId)
			.filter(value -> value.getUserId() == userId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		var courses = courseRepository.findByRoadmapIdOrderByPositionAsc(roadmapId);
		Map<Long, CourseJobRepository.PlanningJob> jobs = new HashMap<>();

		for (var job : courseJobRepository.findUnfinishedPlanningJobs(userId, roadmapId)) {
			jobs.putIfAbsent(job.courseId(), job);
		}

		var items = courses.stream().map(course -> item(course, jobs.get(course.getId()))).toList();
		var next = roadmap.getStatus() == RoadmapStatus.IN_PROGRESS ? nextCourse(courses, jobs) : null;
		return new RoadmapResponse(roadmapId, roadmap.getStatus(), roadmap.getTitle(), roadmap.getDescription(),
			items, next);
	}

	public RoadmapListResponse list(long userId) {
		var items = roadmapRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
			.stream()
			.map(roadmap -> {
				var courses = courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId());
				int completed = (int)courses.stream()
					.filter(course -> course.getStatus() == CourseStatus.COMPLETED)
					.count();
				return new RoadmapListResponse.Item(roadmap.getId(), roadmap.getTitle(), roadmap.getDescription(),
					roadmap.getStatus(), completed, courses.size());
			})
			.toList();
		return new RoadmapListResponse(items);
	}

	private RoadmapResponse.CourseItem item(Course course, CourseJobRepository.PlanningJob job) {
		var progress = CourseProgress.GENERATED;
		Long jobId = null;

		if (course.getStatus() == CourseStatus.NOT_PLANNED) {
			progress = job == null ? CourseProgress.NOT_PLANNED
				: job.failed() ? CourseProgress.FAILED : CourseProgress.GENERATING;
			if (job != null)
				jobId = job.jobId();
		}

		return new RoadmapResponse.CourseItem(course.getId(), course.getPosition(), course.getTitle(),
			course.getPlanDescription(), course.getPlanConcepts(), course.getStatus(), progress, jobId);
	}

	private RoadmapResponse.NextCourse nextCourse(List<Course> courses,
		Map<Long, CourseJobRepository.PlanningJob> jobs) {
		return courses.stream()
			.filter(course -> course.getStatus() == CourseStatus.NOT_PLANNED && !jobs.containsKey(course.getId()))
			.findFirst()
			.map(course -> {
				boolean enabled = course.getPosition() == 1 || courses.stream()
					.anyMatch(previous -> previous.getPosition() == course.getPosition() - 1
						&& previous.getStatus() == CourseStatus.COMPLETED);
				return new RoadmapResponse.NextCourse(course.getId(), enabled);
			})
			.orElse(null);
	}
}
