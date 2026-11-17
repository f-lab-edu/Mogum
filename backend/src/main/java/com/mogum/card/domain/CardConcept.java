package com.mogum.card.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "card_concepts")
public class CardConcept extends AuditedEntity implements Persistable<CardConceptId> {
    @EmbeddedId
    private CardConceptId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", nullable = false, insertable = false, updatable = false)
    private Card card;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concept_id", nullable = false, insertable = false, updatable = false)
    private Concept concept;

    public static CardConcept link(Card card, Concept concept) {
        var link = new CardConcept();
        link.id = new CardConceptId(card.getId(), concept.getId());
        link.card = card;
        link.concept = concept;
        return link;
    }

    // 직접 채운 복합 키도 새 연결이면 INSERT 하여 중복을 DB 기본 키가 막게 한다.
    @Override
    @Transient
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
