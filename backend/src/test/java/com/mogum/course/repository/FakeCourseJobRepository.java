package com.mogum.course.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FakeCourseJobRepository implements CourseJobRepository {
	public final List<PlanningJob> planningJobs = new ArrayList<>();
	public boolean unfinishedReplan;

	@Override
	public List<PlanningJob> findUnfinishedPlanningJobs(long userId, long roadmapId) {
		return List.copyOf(planningJobs);
	}

	@Override
	public boolean hasUnfinishedReplan(long userId, long courseId) {
		return unfinishedReplan;
	}

	@Override
	public Optional<MaterialJob> findMaterialJob(long userId, long lessonId) {
		return Optional.empty();
	}
}
