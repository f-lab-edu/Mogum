package com.mogum.card.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "cards", uniqueConstraints = @UniqueConstraint(
        name = "cards_lesson_id_position_key", columnNames = {"lesson_id", "position"}))
public class Card extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private CardStatus status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "fsrs_state", nullable = false, columnDefinition = "text")
    private FsrsState fsrsState;

    @Column(name = "fsrs_step")
    private Integer fsrsStep;

    @Column
    private Double stability;

    @Column
    private Double difficulty;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    public static Card write(long userId, long lessonId, int position, String question, String answer) {
        var card = new Card();
        card.userId = userId;
        card.lessonId = lessonId;
        card.position = position;
        card.question = question;
        card.answer = answer;
        card.status = CardStatus.ACTIVE;
        card.fsrsState = FsrsState.LEARNING;
        card.fsrsStep = 0;
        return card;
    }
}
