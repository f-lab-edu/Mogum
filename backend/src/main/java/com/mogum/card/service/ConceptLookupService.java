package com.mogum.card.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.domain.Judgment;
import com.mogum.card.repository.ConceptLookupRepository;
import com.mogum.card.repository.ConceptLookupRepository.ConceptSummary;
import com.mogum.card.repository.ConceptLookupRepository.Match;
import com.mogum.config.ConceptProperties;

import lombok.RequiredArgsConstructor;

/** 임베딩은 호출자가 트랜잭션 밖에서 계산한다. 사전과 판정을 읽고 값만 돌려준다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ConceptLookupService {

	private final ConceptLookupRepository conceptLookupRepository;
	private final MasteryJudgmentService masteryJudgmentService;
	private final ConceptProperties conceptProperties;

	public boolean hasConcepts(long userId) {
		return conceptLookupRepository.hasConcepts(userId);
	}

	public List<RelatedConcept> findRelated(long userId, List<float[]> queries, int limit, Instant now) {
		if (queries.isEmpty() || limit <= 0) {
			return List.of();
		}
		var concepts = conceptLookupRepository.findRelated(userId, queries, limit);
		var judgments = masteryJudgmentService.judge(userId, concepts.stream().map(ConceptSummary::id).toList(), now);
		return concepts.stream().map(concept -> new RelatedConcept(concept.name(), concept.definition(),
			judgments.get(concept.id()))).toList();
	}

	public List<Judgment> judgeMatching(long userId, List<float[]> definitions, Instant now) {
		if (definitions.isEmpty()) {
			return List.of();
		}
		var matches = conceptLookupRepository.findMatching(userId, definitions, conceptProperties.sameThreshold());
		var judgments = masteryJudgmentService.judge(userId,
			matches.stream().map(Match::conceptId).distinct().toList(), now);
		var result = new ArrayList<>(Collections.nCopies(definitions.size(), Judgment.NO_RECORD));
		for (var match : matches) {
			result.set(match.position(), judgments.get(match.conceptId()));
		}
		return List.copyOf(result);
	}

	public record RelatedConcept(String name, String definition, Judgment judgment) {
	}
}
