package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mogum.card.service.LessonCardWriter.NewConcept;

class LessonCardWriterTest {

	@Test
	@DisplayName("이름과 정의 및 배열 내용이 같으면 같은 값이고 해시도 같다")
	void comparesEmbeddingContents() {
		// given
		var concept = new NewConcept("개념", "정의", new float[] {1, 2});
		var same = new NewConcept("개념", "정의", new float[] {1, 2});
		// when
		boolean equal = concept.equals(same);
		// then
		assertThat(equal).isTrue();
		assertThat(same).isEqualTo(concept);
		assertThat(concept.hashCode()).isEqualTo(same.hashCode());
	}

	@Test
	@DisplayName("배열 내용이 다르면 다른 값이고 해시에 내용이 반영된다")
	void distinguishesDifferentEmbeddingContents() {
		// given
		var concept = new NewConcept("개념", "정의", new float[] {1, 2});
		var different = new NewConcept("개념", "정의", new float[] {1, 3});
		// when
		boolean equal = concept.equals(different);
		// then
		assertThat(equal).isFalse();
		assertThat(concept.hashCode()).isNotEqualTo(different.hashCode());
	}

	@Test
	@DisplayName("배열 내용이 같아도 이름이나 정의가 다르면 다른 값이다")
	void includesNameAndDefinitionInEquality() {
		// given
		var concept = new NewConcept("개념", "정의", new float[] {1, 2});
		// when & then
		assertThat(concept).isNotEqualTo(new NewConcept("다른 개념", "정의", new float[] {1, 2}));
		assertThat(concept).isNotEqualTo(new NewConcept("개념", "다른 정의", new float[] {1, 2}));
	}

	@Test
	@DisplayName("자기 자신과 같고 null이나 다른 타입과 다르다")
	void preservesEqualityContract() {
		// given
		var concept = new NewConcept("개념", "정의", new float[] {1, 2});
		// when & then
		assertThat(concept).isEqualTo(concept).isNotEqualTo(null).isNotEqualTo("개념");
	}

	@Test
	@DisplayName("문자열에는 벡터 값 대신 차원 수만 보인다")
	void describesOnlyEmbeddingDimensions() {
		// given
		var embedding = new float[3072];
		embedding[0] = 0.125f;
		var concept = new NewConcept("개념", "정의", embedding);
		// when
		String description = concept.toString();
		// then
		assertThat(description).isEqualTo("NewConcept[name=개념, definition=정의, embedding=float[3072]]");
	}

	@Test
	@DisplayName("생성자에 넘긴 배열을 바꿔도 값과 해시가 바뀌지 않는다")
	void copiesEmbeddingOnConstruction() {
		// given
		var embedding = new float[] {1, 2};
		var concept = new NewConcept("개념", "정의", embedding);
		int originalHash = concept.hashCode();
		// when
		embedding[0] = 3;
		// then
		assertThat(concept.embedding()).containsExactly(1, 2);
		assertThat(concept).isEqualTo(new NewConcept("개념", "정의", new float[] {1, 2}));
		assertThat(concept.hashCode()).isEqualTo(originalHash);
	}

	@Test
	@DisplayName("접근자가 돌려준 배열을 바꿔도 값과 해시가 바뀌지 않는다")
	void copiesEmbeddingOnAccess() {
		// given
		var concept = new NewConcept("개념", "정의", new float[] {1, 2});
		var returned = concept.embedding();
		int originalHash = concept.hashCode();
		// when
		returned[0] = 3;
		// then
		assertThat(concept.embedding()).containsExactly(1, 2).isNotSameAs(returned);
		assertThat(concept).isEqualTo(new NewConcept("개념", "정의", new float[] {1, 2}));
		assertThat(concept.hashCode()).isEqualTo(originalHash);
	}
}
