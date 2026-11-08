package com.mogum.learning;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FsrsLibraryLearningTest {
    // FSRS 1.0.0 기본 파라미터: DECAY = -parameters[20], FACTOR = 0.9^(1/DECAY) - 1.
    private static final double DECAY = -0.2;
    private static final double FACTOR = 0.6935087808430285;
    private static final Instant REVIEWED_AT = Instant.parse("2026-10-12T12:34:56Z");

    @Test
    @DisplayName("기본 파라미터를 유지하면서 학습과 재학습 단계를 비운다")
    void keepsDefaultParametersWithoutLearningSteps() {
        // given
        Scheduler defaults = Scheduler.builder().build();

        // when
        Scheduler scheduler = withoutLearningSteps();

        // then
        assertThat(scheduler.getParameters()).containsExactly(defaults.getParameters());
        assertThat(scheduler.getDesiredRetention()).isEqualTo(defaults.getDesiredRetention());
        assertThat(scheduler.getMaximumInterval()).isEqualTo(defaults.getMaximumInterval());
        assertThat(scheduler.isEnableFuzzing()).isEqualTo(defaults.isEnableFuzzing());
        assertThat(scheduler.getLearningSteps()).isEmpty();
        assertThat(scheduler.getRelearningSteps()).isEmpty();
        assertThat(scheduler.getDECAY()).isCloseTo(DECAY, within(1e-15));
        assertThat(scheduler.getFACTOR()).isCloseTo(FACTOR, within(1e-15));
    }

    @ParameterizedTest(name = "평가 {0}")
    @EnumSource(Rating.class)
    @DisplayName("새 카드의 모든 평가는 최소 하루 뒤 복습을 예약한다")
    void schedulesFreshCardAtLeastOneDayLater(Rating rating) {
        // given
        Scheduler scheduler = withoutLearningSteps();
        Card fresh = Card.builder().cardId(1).due(REVIEWED_AT).build();

        // when
        Card reviewed = scheduler.reviewCard(fresh, rating, REVIEWED_AT).card();

        // then
        assertThat(reviewed.getState()).isEqualTo(State.REVIEW);
        assertThat(reviewed.getStep()).isNull();
        assertThat(reviewed.getLastReview()).isEqualTo(REVIEWED_AT);
        Duration interval = Duration.between(REVIEWED_AT, reviewed.getDue());
        assertThat(interval).as(rating + " 다음 복습 간격: " + interval).isGreaterThanOrEqualTo(Duration.ofDays(1));
    }

    @ParameterizedTest(name = "안정도 {0} 경과일 {1}")
    @CsvSource({"0.2172, 1", "1, 7", "7, 7", "16.1507, 30"})
    @DisplayName("복습 카드를 AGAIN으로 평가해도 최소 하루 뒤로 예약한다")
    void schedulesAgainReviewAtLeastOneDayLater(double stability, long elapsedDays) {
        // given
        Scheduler scheduler = withoutLearningSteps();
        Card card = reviewCard(stability, REVIEWED_AT.minus(Duration.ofDays(elapsedDays)));

        // when
        Card reviewed = scheduler.reviewCard(card, Rating.AGAIN, REVIEWED_AT).card();

        // then
        assertThat(reviewed.getState()).isEqualTo(State.REVIEW);
        assertThat(reviewed.getStep()).isNull();
        assertThat(reviewed.getLastReview()).isEqualTo(REVIEWED_AT);
        Duration interval = Duration.between(REVIEWED_AT, reviewed.getDue());
        assertThat(interval).as("AGAIN 다음 복습 간격: " + interval).isGreaterThanOrEqualTo(Duration.ofDays(1));
    }

    @ParameterizedTest(name = "안정도 {0} 경과일 {1}")
    @CsvSource({
            "0.2172, 0", "0.2172, 1", "1, 1", "1, 7",
            "7, 1", "7, 7", "16.1507, 30", "100, 365"
    })
    @DisplayName("인출확률은 정수 경과일에서 SQL 공식과 일치한다")
    void matchesSqlRetrievabilityAtIntegerDays(double stability, long elapsedDays) {
        // given
        Scheduler scheduler = withoutLearningSteps();
        Card card = reviewCard(stability, REVIEWED_AT);
        Instant now = REVIEWED_AT.plus(Duration.ofDays(elapsedDays));
        double expected = Math.pow(1 + FACTOR * elapsedDays / stability, DECAY);

        // when
        double actual = scheduler.getCardRetrievability(card, now);

        // then
        assertThat(actual).as("S=%s, 경과일=%s", stability, elapsedDays).isCloseTo(expected, within(1e-12));
    }

    @ParameterizedTest(name = "경과 시간 {0}시간에서 1ns 차감 {1}")
    @CsvSource({"12, false", "24, true"})
    @DisplayName("하루 미만의 인출확률은 경과일의 소수 부분을 버려 1이다")
    void truncatesFractionalDayBeforeFirstFullDay(long hours, boolean subtractNano) {
        // given
        var scheduler = withoutLearningSteps();
        var card = reviewCard(7.0, REVIEWED_AT);
        var now = REVIEWED_AT.plus(Duration.ofHours(hours)).minusNanos(subtractNano ? 1 : 0);

        // when — 02 §8.3 SQL은 소수 일을 유지하며 여기서는 라이브러리 동작만 고정한다.
        double actual = scheduler.getCardRetrievability(card, now);

        // then
        assertThat(actual).isEqualTo(1.0);
    }

    @Test
    @DisplayName("하루가 지난 카드의 인출확률은 1보다 작다")
    void decreasesRetrievabilityAfterOneFullDay() {
        // given
        var scheduler = withoutLearningSteps();
        var card = reviewCard(7.0, REVIEWED_AT);

        // when
        double actual = scheduler.getCardRetrievability(card, REVIEWED_AT.plus(Duration.ofDays(1)));

        // then
        assertThat(actual).isLessThan(1.0);
    }

    @Test
    @DisplayName("36시간의 인출확률은 소수 부분을 버려 하루와 같다")
    void truncatesFractionalDayAfterOneFullDay() {
        // given
        var scheduler = withoutLearningSteps();
        var card = reviewCard(7.0, REVIEWED_AT);
        double afterOneDay = scheduler.getCardRetrievability(card, REVIEWED_AT.plus(Duration.ofDays(1)));

        // when
        double actual = scheduler.getCardRetrievability(card, REVIEWED_AT.plus(Duration.ofHours(36)));

        // then
        assertThat(actual).isEqualTo(afterOneDay);
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
