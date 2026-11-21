package com.mogum.generation.plan;

import java.util.ArrayList;
import java.util.Optional;

public final class CoursePlanFormat {
    public PlanPrompt prompt(CoursePlanInput input) {
        CoursePlanInput.RoadmapContext context = switch (input) {
            case CoursePlanInput.RoadmapCourse course -> course.context();
            case CoursePlanInput.ReplanCourse course -> course.context();
            case CoursePlanInput.NewCourse ignored -> null;
        };
        var data = PlanPromptSupport.materials(input.input(), context == null || context.position() == 1);
        if (context != null) {
            data.put("coursePlan", context.coursePlan());
            if (context.position() > 1) data.put("previousCourseLessons", context.previousCourseLessons());
            data.put("followingCoursePlans", context.followingCoursePlans());
        }
        if (input instanceof CoursePlanInput.ReplanCourse replan) {
            data.put("previousLessons", replan.previousLessons());
            PlanPromptSupport.optionalText(data, "feedback", replan.feedback());
        }
        return PlanPromptSupport.prompt("course.txt",
                input instanceof CoursePlanInput.NewCourse ? "new-course-output.txt" : "lessons-output.txt", data);
    }

    /** ChatCall.call의 해석 함수로 this::parseNewCourse를 넘긴다. */
    public Optional<CoursePlan> parseNewCourse(String text) {
        return parse(text, true);
    }

    /** 로드맵의 코스 및 다시 짜기는 제목을 받지 않는다. */
    public Optional<CoursePlan> parseLessons(String text) {
        return parse(text, false);
    }

    private Optional<CoursePlan> parse(String text, boolean newCourse) {
        return PlanJson.read(text).flatMap(root -> {
            if (newCourse && !PlanJson.title(root.path("title"))) return Optional.empty();
            var lessons = root.path("lessons");
            if (!lessons.isArray() || lessons.isEmpty() || lessons.size() > 10) return Optional.empty();

            var result = new ArrayList<LessonOutline>();
            for (var lesson : lessons) {
                if (!PlanJson.outline(lesson, false)) return Optional.empty();
                result.add(new LessonOutline(lesson.path("title").asString(), lesson.path("description").asString(),
                        PlanJson.concepts(lesson)));
            }
            return Optional.of(new CoursePlan(newCourse ? root.path("title").asString() : null, result));
        });
    }
}
