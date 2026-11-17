package com.mogum.generation;

import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.plan.CourseOutline;
import com.mogum.generation.plan.PlanningInput;
import com.mogum.generation.plan.RoadmapPlan;
import com.mogum.generation.plan.RoadmapPlanFormat;
import com.mogum.generation.plan.RoadmapPlanInput;
import com.mogum.job.domain.JobType;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 성공 저장 때 검토 중 로드맵의 제목과 설명 및 짜기 전 코스들을 함께 교체한다. */
public final class ReplanRoadmapGenerator implements JobGenerator {
    private final ChatCall chatCall;
    private final ChatSettings chatSettings;
    private final RoadmapPlanFormat roadmapPlanFormat;
    private final CourseRepository courseRepository;
    private final RoadmapRepository roadmapRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public ReplanRoadmapGenerator(ChatCall chatCall, ChatSettings chatSettings, RoadmapPlanFormat roadmapPlanFormat,
            CourseRepository courseRepository, RoadmapRepository roadmapRepository, EntityManager entityManager,
            PlatformTransactionManager platformTransactionManager) {
        this.chatCall = chatCall;
        this.chatSettings = chatSettings;
        this.roadmapPlanFormat = roadmapPlanFormat;
        this.courseRepository = courseRepository;
        this.roadmapRepository = roadmapRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
        this.transactionTemplate.setReadOnly(true);
    }

    @Override
    public JobType type() { return JobType.REPLAN_ROADMAP; }

    @Override
    public GenerationOutcome generate(JobSnapshot job) {
        try {
            var input = transactionTemplate.execute(status -> {
                var roadmap = roadmapRepository.findById(job.targetId()).orElseThrow();
                var values = roadmap.getInput();
                var planning = new PlanningInput(values.getTopic(), values.getLevel(), values.getLevelOther(),
                        values.getPurpose(), values.getPurposeOther(), values.getMemo());
                var courses = courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId()).stream()
                        .map(course -> new CourseOutline(course.getTitle(), course.getPlanDescription(),
                                course.getPlanConcepts()))
                        .toList();
                var previous = new RoadmapPlan(roadmap.getTitle(), roadmap.getDescription(), courses);
                return new RoadmapPlanInput.ReplanRoadmap(planning, previous, job.feedback());
            });
            var prompt = roadmapPlanFormat.prompt(input);
            var result = chatCall.call(job, chatSettings, prompt.system(), prompt.user(), roadmapPlanFormat::parse);
            return switch (result) {
                case ChatResult.Success<RoadmapPlan> success ->
                        new GenerationOutcome.Success(new Result(success.value()));
                case ChatResult.Failed<RoadmapPlan> failed -> failed.failure();
            };
        } catch (RuntimeException ignored) {
            // 원문을 담을 수 있는 예외를 내보내지 않고 작업 실패로 돌린다.
            return GenerationOutcome.Failure.PROVIDER_UNAVAILABLE;
        }
    }

    @Override
    public ResultRef save(JobSnapshot job, GeneratedResult result) {
        var roadmap = roadmapRepository.findById(job.targetId()).orElseThrow();
        var courses = courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId());
        if (courses.stream().anyMatch(course -> course.getStatus() != CourseStatus.NOT_PLANNED)) {
            throw new IllegalStateException("Roadmap courses must be unplanned");
        }
        var plan = ((Result) result).plan();
        roadmap.replan(plan.title(), plan.description());

        courses.forEach(entityManager::remove);
        entityManager.flush(); // 순서 unique와 부딪히지 않도록 DELETE를 INSERT보다 먼저 보낸다.
        for (int index = 0; index < plan.courses().size(); index++) {
            var course = plan.courses().get(index);
            courseRepository.save(Course.inRoadmap(roadmap, index + 1, course.title(), course.description(),
                    course.concepts()));
        }
        return new ResultRef(roadmap.getId());
    }

    public record Result(RoadmapPlan plan) implements GeneratedResult { }
}
