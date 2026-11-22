package com.mogum.generation.plan;

import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;

/** 새 입력 또는 결과에 보관된 입력의 값 사본. 로드맵의 코스는 로드맵의 입력을 쓴다. */
public record PlanningInput(String topic, Level level, String levelOther, Purpose purpose,
	String purposeOther, String memo) {
}
