package com.mogum.generation;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.course.repository.LessonRepository;
import com.mogum.generation.material.MaterialInput;

/** 읽기 트랜잭션 안에서 부모 입력과 강의 목록을 엔티티 없는 값으로 복사한다. */
public final class MaterialReader {
	private final LessonRepository lessonRepository;
	private final TransactionTemplate transactionTemplate;

	public MaterialReader(LessonRepository lessonRepository, PlatformTransactionManager platformTransactionManager) {
		this.lessonRepository = lessonRepository;
		this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
		this.transactionTemplate.setReadOnly(true);
	}

	public MaterialInput read(long lessonId) {
		return transactionTemplate.execute(status -> {
			var lesson = lessonRepository.findById(lessonId).orElseThrow();
			var course = lesson.getCourse();
			var roadmap = course.getRoadmap();
			var input = roadmap == null ? course.getInput() : roadmap.getInput();
			var lessons = lessonRepository.findByCourseIdOrderByPositionAsc(course.getId()).stream()
				.map(line -> new MaterialInput.LessonSummary(line.getPosition(), line.getTitle(), line.getDescription(),
					line.getId() == lessonId))
				.toList();
			return new MaterialInput(course.getTitle(), input.getTopic(),
				new MaterialInput.LessonLine(lesson.getPosition(), lesson.getTitle(), lesson.getDescription(),
					lesson.getConcepts()),
				lessons, input.getPurpose(), input.getPurposeOther(), input.getMemo());
		});
	}
}
