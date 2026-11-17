package com.mogum.generation;

import java.time.Duration;

public record ChatSettings(String model, int maxCompletionTokens, Duration timeout, int maxContinuations) {
    public ChatSettings {
        if (model == null || model.isBlank() || maxCompletionTokens <= 0
                || timeout == null || timeout.isZero() || timeout.isNegative() || maxContinuations < 0) {
            throw new IllegalArgumentException("Invalid chat settings");
        }
    }
}
