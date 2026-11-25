package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mogum.card.domain.Judgment;
import com.mogum.card.service.ConceptLookupService;
import com.mogum.card.service.ConceptLookupService.RelatedConcept;
import com.mogum.job.domain.JobType;

@ExtendWith(MockitoExtension.class)
class RelatedConceptPreparerTest {
	@Mock
	ConceptLookupService conceptLookupService;
	@Mock
	EmbeddingCall embeddingCall;
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
	private final EmbeddingSettings embeddingSettings = new EmbeddingSettings("test-model", Duration.ofSeconds(20));
	private final JobSnapshot job = new JobSnapshot(1L, 2L, JobType.NEW_COURSE, null, null, null, 1);

	@Test
	@DisplayName("사전이 비면 임베딩과 검색 없이 빈 성공 목록을 돌려준다")
	void skipsEmbeddingForEmptyDictionary() {
		// when
		var result = preparer().prepare(job, List.of("주제와 목적"));

		// then
		assertThat(result).isEqualTo(new RelatedConceptPreparer.RelatedResult.Success(List.of()));
		then(embeddingCall).shouldHaveNoInteractions();
		then(conceptLookupService).should().hasConcepts(2L);
		then(conceptLookupService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("질의 전체를 한 번 임베딩하고 최대 20개와 주입 시계의 현재로 검색한다")
	void embedsOnceAndFindsTwentyRelatedConcepts() {
		// given
		var texts = List.of("정의 하나", "정의 둘");
		given(conceptLookupService.hasConcepts(2L)).willReturn(true);
		given(embeddingCall.call(job, embeddingSettings, texts))
			.willReturn(new EmbeddingResult.Success(List.of(List.of(1f, 0f), List.of(0f, 1f))));
		var related = List.of(new RelatedConcept("이름", "정의", Judgment.SHAKY));
		given(conceptLookupService.findRelated(eq(2L), anyList(), eq(20), eq(clock.instant())))
			.willReturn(related);

		// when
		var result = preparer().prepare(job, texts);

		// then
		assertThat(result).isEqualTo(new RelatedConceptPreparer.RelatedResult.Success(related));
		then(embeddingCall).should().call(job, embeddingSettings, texts);
		then(embeddingCall).shouldHaveNoMoreInteractions();
		var vectors = ArgumentCaptor.<List<float[]>>captor();
		then(conceptLookupService).should().findRelated(eq(2L), vectors.capture(), eq(20), eq(clock.instant()));
		assertThat(vectors.getValue()).hasSize(2);
		assertThat(vectors.getValue().get(0)).containsExactly(1f, 0f);
		assertThat(vectors.getValue().get(1)).containsExactly(0f, 1f);
	}

	@ParameterizedTest(name = "실패={0}")
	@EnumSource(GenerationOutcome.Failure.class)
	@DisplayName("임베딩의 실패 갈래를 바꾸지 않고 검색 없이 돌려준다")
	void returnsEmbeddingFailure(GenerationOutcome.Failure failure) {
		// given
		given(conceptLookupService.hasConcepts(2L)).willReturn(true);
		given(embeddingCall.call(job, embeddingSettings, List.of("정의")))
			.willReturn(new EmbeddingResult.Failed(failure));

		// when
		var result = preparer().prepare(job, List.of("정의"));

		// then
		assertThat(result).isEqualTo(new RelatedConceptPreparer.RelatedResult.Failed(failure));
		then(conceptLookupService).should().hasConcepts(2L);
		then(conceptLookupService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("예상 밖 예외는 실패 값으로 숨기지 않고 생성기의 공통 실패 경로에 맡긴다")
	void propagatesUnexpectedException() {
		// given
		var exception = new IllegalStateException("표식");
		given(conceptLookupService.hasConcepts(2L)).willReturn(true);
		given(embeddingCall.call(job, embeddingSettings, List.of("정의"))).willThrow(exception);

		// when & then
		assertThatThrownBy(() -> preparer().prepare(job, List.of("정의"))).isSameAs(exception);
	}

	private RelatedConceptPreparer preparer() {
		return new RelatedConceptPreparer(conceptLookupService, embeddingCall, embeddingSettings, clock);
	}
}
