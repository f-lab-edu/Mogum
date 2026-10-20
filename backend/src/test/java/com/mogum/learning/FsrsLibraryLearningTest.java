package com.mogum.learning;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FsrsLibraryLearningTest {
    // FSRS 1.0.0 기본 파라미터: DECAY = -parameters[20], FACTOR = 0.9^(1/DECAY) - 1.
    private static final double DECAY = -0.2;
    private static final double FACTOR = 0.6935087808430285;
    private static final Instant REVIEWED_AT = Instant.parse("2026-10-12T12:34:56Z");

    @Test
    void 기본_파라미터를_유지하면서_학습과_재학습_단계를_비운다() {
        Scheduler defaults = Scheduler.builder().build();
        Scheduler scheduler = withoutLearningSteps();

        assertArrayEquals(defaults.getParameters(), scheduler.getParameters());
        assertEquals(defaults.getDesiredRetention(), scheduler.getDesiredRetention());
        assertEquals(defaults.getMaximumInterval(), scheduler.getMaximumInterval());
        assertEquals(defaults.isEnableFuzzing(), scheduler.isEnableFuzzing());
        assertEquals(0, scheduler.getLearningSteps().length);
        assertEquals(0, scheduler.getRelearningSteps().length);
        assertEquals(DECAY, scheduler.getDECAY(), 1e-15);
        assertEquals(FACTOR, scheduler.getFACTOR(), 1e-15);
    }

    @ParameterizedTest
    @EnumSource(Rating.class)
    void 새_카드의_모든_평가는_최소_하루_뒤_복습을_예약한다(Rating rating) {
        Scheduler scheduler = withoutLearningSteps();
        Card fresh = Card.builder().cardId(1).due(REVIEWED_AT).build();

        Card reviewed = scheduler.reviewCard(fresh, rating, REVIEWED_AT).card();

        assertEquals(State.REVIEW, reviewed.getState());
        assertNull(reviewed.getStep());
        assertEquals(REVIEWED_AT, reviewed.getLastReview());
        Duration interval = Duration.between(REVIEWED_AT, reviewed.getDue());
        assertTrue(interval.compareTo(Duration.ofDays(1)) >= 0,
                () -> rating + " 다음 복습 간격: " + interval);
    }

    @ParameterizedTest
    @CsvSource({"0.2172, 1", "1, 7", "7, 7", "16.1507, 30"})
    void 복습_카드를_AGAIN으로_평가해도_최소_하루_뒤로_예약한다(double stability, long elapsedDays) {
        Scheduler scheduler = withoutLearningSteps();
        Card card = reviewCard(stability, REVIEWED_AT.minus(Duration.ofDays(elapsedDays)));

        Card reviewed = scheduler.reviewCard(card, Rating.AGAIN, REVIEWED_AT).card();

        assertEquals(State.REVIEW, reviewed.getState());
        assertNull(reviewed.getStep());
        assertEquals(REVIEWED_AT, reviewed.getLastReview());
        Duration interval = Duration.between(REVIEWED_AT, reviewed.getDue());
        assertTrue(interval.compareTo(Duration.ofDays(1)) >= 0,
                () -> "AGAIN 다음 복습 간격: " + interval);
    }

    @ParameterizedTest
    @CsvSource({
            "0.2172, 0", "0.2172, 1", "1, 1", "1, 7",
            "7, 1", "7, 7", "16.1507, 30", "100, 365"
    })
    void 인출확률은_정수_경과일에서_SQL_공식과_일치한다(double stability, long elapsedDays) {
        Scheduler scheduler = withoutLearningSteps();
        Card card = reviewCard(stability, REVIEWED_AT);
        Instant now = REVIEWED_AT.plus(Duration.ofDays(elapsedDays));
        double expected = Math.pow(1 + FACTOR * elapsedDays / stability, DECAY);

        assertEquals(expected, scheduler.getCardRetrievability(card, now), 1e-12,
                () -> "S=" + stability + ", 경과일=" + elapsedDays);
    }

    @Test
    void 라이브러리의_인출확률은_경과일의_소수_부분을_버린다() {
        Scheduler scheduler = withoutLearningSteps();
        Card card = reviewCard(7.0, REVIEWED_AT);

        // 02 §8.3 SQL은 소수 일을 유지한다. 이 테스트는 라이브러리의 절삭 동작만 고정한다.
        assertEquals(1.0, scheduler.getCardRetrievability(card,
                REVIEWED_AT.plus(Duration.ofHours(12))));
        assertEquals(1.0, scheduler.getCardRetrievability(card,
                REVIEWED_AT.plus(Duration.ofDays(1)).minusNanos(1)));
        double afterOneDay = scheduler.getCardRetrievability(card,
                REVIEWED_AT.plus(Duration.ofDays(1)));
        assertTrue(afterOneDay < 1.0);
        assertEquals(afterOneDay, scheduler.getCardRetrievability(card,
                REVIEWED_AT.plus(Duration.ofHours(36))));
    }

    private static Scheduler withoutLearningSteps() {
        return Scheduler.builder()
                .learningSteps(new Duration[0])
                .relearningSteps(new Duration[0])
                .build();
    }

    private static Card reviewCard(double stability, Instant lastReview) {
        return Card.builder()
                .cardId(1)
                .state(State.REVIEW)
                .step(null)
                .stability(stability)
                .difficulty(5.0)
                .due(REVIEWED_AT)
                .lastReview(lastReview)
                .build();
    }
}
