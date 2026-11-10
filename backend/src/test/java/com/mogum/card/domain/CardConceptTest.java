package com.mogum.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardConceptTest {
    @Test
    @DisplayName("카드의 개념 연결은 두 부모와 두 ID의 복합 키를 담는다")
    void linksCardToConcept() {
        // given
        var card = CardFixtures.withId(17L);
        var concept = ConceptFixtures.withId(23L);

        // when
        var link = CardConcept.link(card, concept);

        // then
        assertThat(link.getId()).isEqualTo(new CardConceptId(17L, 23L));
        assertThat(link.getId().cardId()).isEqualTo(17L);
        assertThat(link.getId().conceptId()).isEqualTo(23L);
        assertThat(link.getCard()).isSameAs(card);
        assertThat(link.getConcept()).isSameAs(concept);
        assertThat(link.getCreatedAt()).isNull();
        assertThat(link.getUpdatedAt()).isNull();
        assertThat(link.isNew()).isTrue();
    }
}
