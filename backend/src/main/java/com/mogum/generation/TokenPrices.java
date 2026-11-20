package com.mogum.generation;

import java.math.BigDecimal;

/** USD / 1M 토큰. 사용량 원문이나 누적 비용은 보관하지 않는다. */
public record TokenPrices(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {
    public TokenPrices {
        if (input == null || cachedInput == null || output == null
                || input.signum() < 0 || cachedInput.signum() < 0 || output.signum() < 0) {
            throw new IllegalArgumentException("Invalid token prices");
        }
    }

    public BigDecimal cost(ChatProvider.Usage usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) return null;

        int cached = usage.cachedInputTokens() == null ? 0 : usage.cachedInputTokens();
        return input.multiply(BigDecimal.valueOf((long) usage.inputTokens() - cached))
                .add(cachedInput.multiply(BigDecimal.valueOf(cached)))
                .add(output.multiply(BigDecimal.valueOf(usage.outputTokens())))
                .movePointLeft(6);
    }
}
