package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mogum.card.domain.Judgment;
import com.mogum.card.repository.ConceptLookupRepository;
import com.mogum.card.repository.ConceptLookupRepository.ConceptSummary;
import com.mogum.card.repository.ConceptLookupRepository.Match;
import com.mogum.card.service.ConceptLookupService.RelatedConcept;
import com.mogum.config.ConceptProperties;

@ExtendWith(MockitoExtension.class)
class ConceptLookupServiceTest {

	@Mock
	ConceptLookupRepository conceptLookupRepository;
	@Mock
	MasteryJudgmentService masteryJudgmentService;

	@Test
	@DisplayName("관련 개념을 한 번에 검색하고 고른 모든 개념의 판정을 한 번에 요청한다")
	void batchesRelatedSearchAndJudgment() {
		// given
		var service = new ConceptLookupService(conceptLookupRepository, masteryJudgmentService,
			new ConceptProperties(0.8));
		var queries = List.of(new float[] {1, 0}, new float[] {0, 1});
		var now = Instant.parse("2026-11-25T00:00:00Z");
		given(conceptLookupRepository.findRelated(7L, queries, 20))
			.willReturn(List.of(new ConceptSummary(12L, "첫째", "첫 정의"), new ConceptSummary(8L, "둘째", "둘째 정의")));
		given(masteryJudgmentService.judge(7L, List.of(12L, 8L), now))
			.willReturn(Map.of(12L, Judgment.SHAKY, 8L, Judgment.WELL_KNOWN));

		// when
		var actual = service.findRelated(7L, queries, 20, now);

		// then
		assertThat(actual).containsExactly(new RelatedConcept("첫째", "첫 정의", Judgment.SHAKY),
			new RelatedConcept("둘째", "둘째 정의", Judgment.WELL_KNOWN));
		then(conceptLookupRepository).should().findRelated(7L, queries, 20);
		then(conceptLookupRepository).shouldHaveNoMoreInteractions();
		then(masteryJudgmentService).should().judge(7L, List.of(12L, 8L), now);
		then(masteryJudgmentService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("정의를 설정된 기준값으로 한 번에 맞추고 중복을 뺀 개념의 판정을 한 번에 요청한다")
	void batchesMatchingAndJudgmentUsingConfiguredThreshold() {
		// given
		var service = new ConceptLookupService(conceptLookupRepository, masteryJudgmentService,
			new ConceptProperties(0.8));
		var definitions = List.of(new float[] {1, 0}, new float[] {0, 1}, new float[] {1, 0}, new float[] {-1, 0});
		var now = Instant.parse("2026-11-25T00:00:00Z");
		given(conceptLookupRepository.findMatching(7L, definitions, 0.8))
			.willReturn(List.of(new Match(0, 12L), new Match(2, 12L), new Match(3, 8L)));
		given(masteryJudgmentService.judge(7L, List.of(12L, 8L), now))
			.willReturn(Map.of(12L, Judgment.SHAKY, 8L, Judgment.WELL_KNOWN));

		// when
		var actual = service.judgeMatching(7L, definitions, now);

		// then
		assertThat(actual).containsExactly(Judgment.SHAKY, Judgment.NO_RECORD, Judgment.SHAKY, Judgment.WELL_KNOWN);
		then(conceptLookupRepository).should().findMatching(7L, definitions, 0.8);
		then(conceptLookupRepository).shouldHaveNoMoreInteractions();
		then(masteryJudgmentService).should().judge(7L, List.of(12L, 8L), now);
		then(masteryJudgmentService).shouldHaveNoMoreInteractions();
	}
}
