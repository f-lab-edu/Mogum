package com.mogum.generation;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Lesson;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.generation.plan.CoursePlan;
import com.mogum.generation.plan.CoursePlanFormat;
import com.mogum.generation.plan.CoursePlanInput;
import com.mogum.generation.plan.PlanningInput;
import com.mogum.job.domain.JobType;

import lombok.RequiredArgsConstructor;

/** 새 코스의 강의 목록을 만들고 워커가 소유권을 확인한 트랜잭션에서 저장한다. */
@RequiredArgsConstructor
public final class NewCourseGenerator implements JobGenerator {
	private final ChatCall chatCall;
	private final ChatSettings chatSettings;
	private final CoursePlanFormat coursePlanFormat;
	private final CourseRepository courseRepository;
	private final LessonRepository lessonRepository;

	@Override
	public JobType type() {
		return JobType.NEW_COURSE;
	}

	@Override
	public GenerationOutcome generate(JobSnapshot job) {
		try {
			var input = job.input();
			var planning = new PlanningInput(input.topic(), input.level(), input.levelOther(),
				input.purpose(), input.purposeOther(), input.memo());
			var prompt = coursePlanFormat.prompt(new CoursePlanInput.NewCourse(planning));

			var result = chatCall.call(job, chatSettings, prompt.system(), prompt.user(),
				coursePlanFormat::parseNewCourse);
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
		var plan = ((Result)result).plan();
		var values = job.input();
		var input = new LearningInput(values.topic(), values.level(), values.levelOther(),
			values.purpose(), values.purposeOther(), values.memo());
		var course = courseRepository.save(Course.single(job.userId(), input, plan.title()));

		for (int index = 0; index < plan.lessons().size(); index++) {
			var lesson = plan.lessons().get(index);
			lessonRepository.save(Lesson.listed(course, index + 1, lesson.title(), lesson.description(),
				lesson.concepts()));
		}

		return new ResultRef(course.getId());
	}

	public record Result(CoursePlan plan) implements GeneratedResult {
	}
}
