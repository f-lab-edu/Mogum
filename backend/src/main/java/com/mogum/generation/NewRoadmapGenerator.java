package com.mogum.generation;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Roadmap;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.plan.PlanningInput;
import com.mogum.generation.plan.RoadmapPlan;
import com.mogum.generation.plan.RoadmapPlanFormat;
import com.mogum.generation.plan.RoadmapPlanInput;
import com.mogum.job.domain.JobType;
import lombok.RequiredArgsConstructor;

/** 새 로드맵 계획을 만들고 워커가 소유권을 확인한 트랜잭션에서 저장한다. */
@RequiredArgsConstructor
public final class NewRoadmapGenerator implements JobGenerator {
    private final ChatCall chatCall;
    private final ChatSettings chatSettings;
    private final RoadmapPlanFormat roadmapPlanFormat;
    private final CourseRepository courseRepository;
    private final RoadmapRepository roadmapRepository;

    @Override
    public JobType type() { return JobType.NEW_ROADMAP; }

    @Override
    public GenerationOutcome generate(JobSnapshot job) {
        try {
            var input = job.input();
            var planning = new PlanningInput(input.topic(), input.level(), input.levelOther(),
                    input.purpose(), input.purposeOther(), input.memo());
            var prompt = roadmapPlanFormat.prompt(new RoadmapPlanInput.NewRoadmap(planning));

            var result = chatCall.call(job, chatSettings, prompt.system(), prompt.user(),
                    roadmapPlanFormat::parse);
            return switch (result) {
                case ChatResult.Success<RoadmapPlan> success ->
                        new GenerationOutcome.Success(new Result(success.value()));
                case ChatResult.Failed<RoadmapPlan> failed -> failed.failure();
            };
        } catch (RuntimeException exception) {
            return GenerationFailures.unavailable(exception);
        }
    }

    @Override
    public ResultRef save(JobSnapshot job, GeneratedResult result) {
        var plan = ((Result) result).plan();
        var values = job.input();
        var input = new LearningInput(values.topic(), values.level(), values.levelOther(),
                values.purpose(), values.purposeOther(), values.memo());
        var roadmap = roadmapRepository.save(Roadmap.plan(job.userId(), input, plan.title(), plan.description()));

        for (int index = 0; index < plan.courses().size(); index++) {
            var course = plan.courses().get(index);
            courseRepository.save(Course.inRoadmap(roadmap, index + 1, course.title(), course.description(),
                    course.concepts()));
        }

        return new ResultRef(roadmap.getId());
    }

    public record Result(RoadmapPlan plan) implements GeneratedResult { }
}
