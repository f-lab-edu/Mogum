package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenPricesTest {
	private final TokenPrices tokenPrices = new TokenPrices(new BigDecimal("2.00"),
		new BigDecimal("0.20"), new BigDecimal("12.00"));

	@Test
	@DisplayName("캐시된 입력 단가를 따로 적용하여 USD 비용을 계산한다")
	void discountsCachedInput() {
		// when
		var cost = tokenPrices.cost(new ChatProvider.Usage(100, 40, 20));

		// then
		assertThat(cost).isEqualByComparingTo("0.000368");
	}

	@Test
	@DisplayName("사용량이 없으면 비용도 비운다")
	void leavesUnknownUsageUnpriced() {
		// when
		var cost = tokenPrices.cost(null);

		// then
		assertThat(cost).isNull();
	}

	@Test
	@DisplayName("캐시 사용량을 주지 않으면 입력 전체에 일반 단가를 적용한다")
	void pricesInputWithoutCacheDetails() {
		// when
		var cost = tokenPrices.cost(new ChatProvider.Usage(100, null, 20));

		// then
		assertThat(cost).isEqualByComparingTo("0.00044");
	}
}
