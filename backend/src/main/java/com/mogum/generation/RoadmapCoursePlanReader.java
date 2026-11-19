package com.mogum.generation;

import com.mogum.course.domain.Course;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.generation.plan.CourseOutline;
import com.mogum.generation.plan.CoursePlanInput;
import com.mogum.generation.plan.CoursePlanInput.RoadmapContext;
import com.mogum.generation.plan.LessonOutline;
import com.mogum.generation.plan.PlanningInput;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 로드맵 코스의 계획 재료를 읽기 트랜잭션에서 값으로 복사한다. 다시 짜기도 같은 재료를 쓴다. */
public final class RoadmapCoursePlanReader {
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final TransactionTemplate transactionTemplate;

    public RoadmapCoursePlanReader(
            CourseRepository courseRepository, LessonRepository lessonRepository,
            PlatformTransactionManager platformTransactionManager
    ) {
        this.courseRepository = courseRepository;
        this.lessonRepository = lessonRepository;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
        this.transactionTemplate.setReadOnly(true);
    }

    public CoursePlanInput.RoadmapCourse read(long courseId) {
        return transactionTemplate.execute(status -> {
            var course = courseRepository.findById(courseId).orElseThrow();
            var roadmap = course.getRoadmap();
            var input = roadmap.getInput();
            int position = course.getPosition();
            var planning = new PlanningInput(input.getTopic(), position == 1 ? input.getLevel() : null,
                    position == 1 ? input.getLevelOther() : null, input.getPurpose(), input.getPurposeOther(),
                    input.getMemo());
            var courses = courseRepository.findByRoadmapIdOrderByPositionAsc(roadmap.getId());
            List<LessonOutline> previousLessons = position == 1 ? List.of() : courses
                    .stream()
                    .filter(previous -> previous.getPosition() == position - 1)
                    .findFirst()
                    .map(previous -> lessonRepository.findByCourseIdOrderByPositionAsc(previous.getId())
                            .stream()
                            .map(lesson -> new LessonOutline(lesson.getTitle(), lesson.getDescription(),
                                    lesson.getConcepts()))
                            .toList())
                    .orElseThrow();
            var following = courses.stream()
                    .filter(next -> next.getPosition() > position)
                    .map(RoadmapCoursePlanReader::outline)
                    .toList();
            return new CoursePlanInput.RoadmapCourse(planning,
                    new RoadmapContext(position, outline(course), previousLessons, following));
        });
    }

    private static CourseOutline outline(Course course) {
        return new CourseOutline(course.getTitle(), course.getPlanDescription(), course.getPlanConcepts());
    }
}
