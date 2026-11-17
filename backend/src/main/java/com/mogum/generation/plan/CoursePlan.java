package com.mogum.generation.plan;

import java.util.List;

/** title은 새 코스에서만 받는다. 기존 코스의 제목은 결과에서 바꾸지 않는다. */
public record CoursePlan(String title, List<LessonOutline> lessons) {
    public CoursePlan {
        lessons = List.copyOf(lessons);
    }
}
