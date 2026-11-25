package com.mogum.generation;

import com.mogum.course.domain.Lesson;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.generation.plan.CoursePlan;
import com.mogum.generation.plan.CoursePlanFormat;
import com.mogum.job.domain.JobType;

import lombok.RequiredArgsConstructor;

/** 로드맵의 첫 코스와 다음 코스에 강의 목록을 채운다. 제목과 계획은 바꾸지 않는다. */
@RequiredArgsConstructor
public final class PlanRoadmapCourseGenerator implements JobGenerator {
	private final ChatCall chatCall;
	private final ChatSettings chatSettings;
	private final CoursePlanFormat coursePlanFormat;
	private final RelatedConceptPreparer relatedConceptPreparer;
	private final RoadmapCoursePlanReader roadmapCoursePlanReader;
	private final CourseRepository courseRepository;
	private final LessonRepository lessonRepository;

	@Override
	public JobType type() {
		return JobType.PLAN_ROADMAP_COURSE;
	}

	@Override
	public GenerationOutcome generate(JobSnapshot job) {
		try {
			var input = roadmapCoursePlanReader.read(job.targetId());
			var related = relatedConceptPreparer.prepare(job, input.queryTexts());
			if (related instanceof RelatedConceptPreparer.RelatedResult.Failed failed) {
				return failed.failure();
			}
			var prompt = coursePlanFormat.prompt(input.withRelatedConcepts(
				((RelatedConceptPreparer.RelatedResult.Success)related).concepts()));
			var result = chatCall.call(job, chatSettings, prompt.system(), prompt.user(),
				coursePlanFormat::parseLessons);
			return switch (result) {
				case ChatResult.Success<CoursePlan> success ->
					new GenerationOutcome.Success(new Result(success.value()));
				case ChatResult.Failed<CoursePlan> failed -> failed.failure();
			};
		} catch (RuntimeException exception) {
			return GenerationFailures.unavailable(exception);
		}
	}

	@Override
	public ResultRef save(JobSnapshot job, GeneratedResult result) {
		var course = courseRepository.findById(job.targetId()).orElseThrow();
		var plan = ((Result)result).plan();
		course.planLessons(plan.masteryReport());
		var lessons = plan.lessons();
		for (int index = 0; index < lessons.size(); index++) {
			var lesson = lessons.get(index);
			lessonRepository.save(Lesson.listed(course, index + 1, lesson.title(), lesson.description(),
				lesson.concepts()));
		}
		return new ResultRef(course.getId());
	}

	public record Result(CoursePlan plan) implements GeneratedResult {
	}
}
