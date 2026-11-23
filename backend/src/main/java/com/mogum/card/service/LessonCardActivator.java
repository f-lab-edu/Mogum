package com.mogum.card.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.repository.CardRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class LessonCardActivator {
	private final CardRepository cardRepository;

	public void activate(long lessonId, Instant completedAt) {
		cardRepository.activateLessonCards(lessonId, completedAt);
	}
}
