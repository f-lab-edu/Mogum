package com.mogum.course.domain;

import java.util.List;

/** LLM이 적은 짧게 다룬 개념과 다시 짚은 개념의 이름을 그대로 담는다. */
public record MasteryReport(List<String> shortened, List<String> revisited) {
	public MasteryReport {
		shortened = List.copyOf(shortened);
		revisited = List.copyOf(revisited);
	}
}
