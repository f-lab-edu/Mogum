package com.mogum.generation.plan;

import java.util.List;

public sealed interface CoursePlanInput {
    PlanningInput input();

    record NewCourse(PlanningInput input) implements CoursePlanInput { }

    record RoadmapCourse(PlanningInput input, RoadmapContext context) implements CoursePlanInput { }

    /** context가 없으면 단일 코스다. 피드백이 없어도 이전 목록은 넣는다. */
    record ReplanCourse(
            PlanningInput input, RoadmapContext context, List<LessonOutline> previousLessons,
            String feedback
    ) implements CoursePlanInput {
        public ReplanCourse {
            previousLessons = List.copyOf(previousLessons);
        }
    }

    /** 앞 코스 계획 없이, 바로 앞 코스 한 개의 실제 목록과 뒤 코스들의 계획만 받는다. */
    record RoadmapContext(
            int position, CourseOutline coursePlan, List<LessonOutline> previousCourseLessons,
            List<CourseOutline> followingCoursePlans
    ) {
        public RoadmapContext {
            if (position < 1) throw new IllegalArgumentException("Course position must be positive");
            previousCourseLessons = List.copyOf(previousCourseLessons);
            followingCoursePlans = List.copyOf(followingCoursePlans);
        }
    }
}
