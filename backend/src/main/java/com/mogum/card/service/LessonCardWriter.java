package com.mogum.card.service;

import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardConcept;
import com.mogum.card.domain.Concept;
import com.mogum.card.repository.CardConceptRepository;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ConceptRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.config.ConceptProperties;

import lombok.RequiredArgsConstructor;

/** 카드와 개념 사전의 유일한 쓰기 경로. 임베딩은 호출자가 트랜잭션 밖에서 계산해 넘긴다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class LessonCardWriter {
	private final UserRepository userRepository;
	private final CardRepository cardRepository;
	private final ConceptRepository conceptRepository;
	private final CardConceptRepository cardConceptRepository;
	private final ConceptProperties conceptProperties;

	public void writeLessonCards(long userId, long lessonId, List<NewCard> cards) {
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		int position = 1;
		for (var input : cards) {
			var card = cardRepository.save(Card.write(userId, lessonId, position++, input.question(), input.answer()));
			var linkedConcepts = new HashSet<Long>();
			for (var inputConcept : input.concepts()) {
				var concept = conceptRepository
					.findSameConcept(userId, inputConcept.embedding(), conceptProperties.sameThreshold())
					.orElseGet(() -> conceptRepository.save(Concept.add(userId, inputConcept.name(),
						inputConcept.definition(), inputConcept.embedding())));
				if (linkedConcepts.add(concept.getId())) {
					cardConceptRepository.save(CardConcept.link(card, concept));
				}
			}
		}
	}

	public record NewCard(String question, String answer, List<NewConcept> concepts) {
	}

	public record NewConcept(String name, String definition, float[] embedding) {
	}
}
