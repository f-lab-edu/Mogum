package com.mogum.card.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.domain.Judgment;
import com.mogum.card.repository.CardConceptRepository;
import com.mogum.card.service.MasteryJudgmentCalculator.CardMemory;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MasteryJudgmentService {
	private final CardConceptRepository cardConceptRepository;
	private final FsrsCalculator fsrsCalculator;

	public Map<Long, Judgment> judge(long userId, Collection<Long> conceptIds, Instant now) {
		Objects.requireNonNull(now, "now");
		if (conceptIds.isEmpty()) {
			return Map.of();
		}
		var memories = new HashMap<Long, List<CardMemory>>();
		conceptIds.forEach(id -> memories.put(id, new ArrayList<>()));
		for (var link : cardConceptRepository.findReviewedByConceptIds(userId, memories.keySet())) {
			var card = link.getCard();
			memories.get(link.getId().conceptId())
				.add(new CardMemory(fsrsCalculator.retrievability(card, now), card.getStability()));
		}
		var judgments = new HashMap<Long, Judgment>();
		memories.forEach((id, cards) -> judgments.put(id, MasteryJudgmentCalculator.judge(cards)));
		return Map.copyOf(judgments);
	}
}
