package com.mogum.generation;

import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Lesson;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.generation.plan.CoursePlan;
import com.mogum.generation.plan.CoursePlanFormat;
import com.mogum.generation.plan.CoursePlanInput;
import com.mogum.generation.plan.LessonOutline;
import com.mogum.generation.plan.PlanningInput;
import com.mogum.job.domain.JobType;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 성공 저장 때만 강의를 교체한다. 단일 코스와 로드맵 코스 모두 제목을 유지한다. */
public final class ReplanCourseGenerator implements JobGenerator {
    private final ChatCall chatCall;
    private final ChatSettings chatSettings;
    private final CoursePlanFormat coursePlanFormat;
    private final RoadmapCoursePlanReader roadmapCoursePlanReader;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public ReplanCourseGenerator(
            ChatCall chatCall, ChatSettings chatSettings, CoursePlanFormat coursePlanFormat,
            RoadmapCoursePlanReader roadmapCoursePlanReader, CourseRepository courseRepository,
            LessonRepository lessonRepository, EntityManager entityManager,
            PlatformTransactionManager platformTransactionManager
    ) {
        this.chatCall = chatCall;
        this.chatSettings = chatSettings;
        this.coursePlanFormat = coursePlanFormat;
        this.roadmapCoursePlanReader = roadmapCoursePlanReader;
        this.courseRepository = courseRepository;
        this.lessonRepository = lessonRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
        this.transactionTemplate.setReadOnly(true);
    }

    @Override
    public JobType type() { return JobType.REPLAN_COURSE; }

    @Override
    public GenerationOutcome generate(JobSnapshot job) {
        try {
            var input = transactionTemplate.execute(status -> {
                var course = courseRepository.findById(job.targetId()).orElseThrow();
                var previous = lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).stream()
                        .map(lesson -> new LessonOutline(lesson.getTitle(), lesson.getDescription(),
                                lesson.getConcepts()))
                        .toList();
                if (course.getRoadmap() != null) {
                    var materials = roadmapCoursePlanReader.read(course.getId());
                    return new CoursePlanInput.ReplanCourse(materials.input(), materials.context(), previous,
                            job.feedback());
                }
                var values = course.getInput();
                var planning = new PlanningInput(values.getTopic(), values.getLevel(), values.getLevelOther(),
                        values.getPurpose(), values.getPurposeOther(), values.getMemo());
                return new CoursePlanInput.ReplanCourse(planning, null, previous, job.feedback());
            });
            var prompt = coursePlanFormat.prompt(input);
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
        if (course.getStatus() != CourseStatus.REVIEWING) throw new IllegalStateException("Course must be reviewing");

        lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).forEach(entityManager::remove);
        entityManager.flush(); // 순서 unique와 부딪히지 않도록 DELETE를 INSERT보다 먼저 보낸다.
        var lessons = ((Result) result).plan().lessons();
        for (int index = 0; index < lessons.size(); index++) {
            var lesson = lessons.get(index);
            lessonRepository.save(Lesson.listed(course, index + 1, lesson.title(), lesson.description(),
                    lesson.concepts()));
        }
        return new ResultRef(course.getId());
    }

    public record Result(CoursePlan plan) implements GeneratedResult { }
}
