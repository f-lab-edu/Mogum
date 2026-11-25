package com.mogum.card.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.card.dto.StreakResponse;
import com.mogum.card.repository.StreakActivityRepository;
import com.mogum.common.time.SeoulClock;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StreakReadService {
	private final StreakActivityRepository streakActivityRepository;
	private final Clock clock;

	public StreakResponse read(long userId) {
		var today = new SeoulClock(clock).today();
		var filledDays = streakActivityRepository.findFilledDays(userId, today);
		boolean filledToday = !filledDays.isEmpty() && filledDays.getFirst().equals(today);
		var expected = filledToday ? today : today.minusDays(1);
		int days = 0;
		for (var filledDay : filledDays) {
			if (!filledDay.equals(expected)) {
				break;
			}
			days++;
			expected = expected.minusDays(1);
		}
		return new StreakResponse(days, filledToday);
	}
}
