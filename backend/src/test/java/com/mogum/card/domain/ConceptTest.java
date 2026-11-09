package com.mogum.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptTest {
    @Test
    @DisplayName("개념은 주인과 이름 및 정의와 임베딩을 담는다")
    void addsConceptToUserDictionary() {
        // given
        var embedding = new float[]{0.25f, -0.5f, 1.125f};

        // when
        var concept = Concept.add(8L, "변수", "값을 담는 이름", embedding);

        // then
        assertThat(concept.getId()).isNull();
        assertThat(concept.getCreatedAt()).isNull();
        assertThat(concept.getUpdatedAt()).isNull();
        assertThat(concept.getUserId()).isEqualTo(8L);
        assertThat(concept.getName()).isEqualTo("변수");
        assertThat(concept.getDefinition()).isEqualTo("값을 담는 이름");
        assertThat(concept.getEmbedding()).containsExactly(embedding);
    }
}
