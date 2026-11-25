package com.mogum.generation;

import java.time.Clock;
import java.util.List;

import com.mogum.card.service.ConceptLookupService;
import com.mogum.card.service.ConceptLookupService.RelatedConcept;

import lombok.RequiredArgsConstructor;

/** 계획 생성의 읽기 트랜잭션이 끝난 뒤 관련 개념과 판정을 준비한다. */
@RequiredArgsConstructor
public final class RelatedConceptPreparer {
	private static final int RELATED_CONCEPT_LIMIT = 20;
	private final ConceptLookupService conceptLookupService;
	private final EmbeddingCall embeddingCall;
	private final EmbeddingSettings embeddingSettings;
	private final Clock clock;

	public RelatedResult prepare(JobSnapshot job, List<String> queryTexts) {
		if (!conceptLookupService.hasConcepts(job.userId())) {
			return new RelatedResult.Success(List.of());
		}
		var embedding = embeddingCall.call(job, embeddingSettings, queryTexts);
		if (embedding instanceof EmbeddingResult.Failed failed) {
			return new RelatedResult.Failed(failed.failure());
		}
		var vectors = ((EmbeddingResult.Success)embedding).value().stream()
			.map(RelatedConceptPreparer::vector).toList();
		return new RelatedResult.Success(conceptLookupService.findRelated(job.userId(), vectors,
			RELATED_CONCEPT_LIMIT, clock.instant()));
	}

	private static float[] vector(List<Float> values) {
		var vector = new float[values.size()];
		for (int dimension = 0; dimension < vector.length; dimension++) {
			vector[dimension] = values.get(dimension);
		}
		return vector;
	}

	public sealed interface RelatedResult {
		record Success(List<RelatedConcept> concepts) implements RelatedResult {
			public Success {
				concepts = List.copyOf(concepts);
			}
		}

		record Failed(GenerationOutcome.Failure failure) implements RelatedResult {
		}
	}
}
