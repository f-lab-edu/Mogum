package com.mogum.generation.material;

import java.util.List;

import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;

/** 강의 자료에 허용된 재료만 담는다. 수준·피드백·다른 강의의 개념은 없다. */
public record MaterialInput(String courseTitle, String topic, LessonLine lesson, List<LessonSummary> lessons,
	Purpose purpose, String purposeOther, String memo) {
	public MaterialInput {
		lessons = List.copyOf(lessons);
	}

	public record LessonLine(int position, String title, String description, List<PlannedConcept> concepts) {
		public LessonLine {
			concepts = List.copyOf(concepts);
		}
	}

	public record LessonSummary(int position, String title, String description, boolean current) {
	}
}
