package com.mogum.config;

import com.mogum.generation.ChatSettings;
import com.mogum.generation.TokenPrices;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("app.llm")
public record LlmProperties(Openai openai, Map<String, ChatSettings> chats, Map<String, TokenPrices> prices,
        Duration databaseAllowance) {
    private static final String PLANNING_CHAT = "planning";

    public LlmProperties {
        Assert.notNull(openai, "OpenAI settings must be set");
        Assert.notEmpty(chats, "Chat settings must be set");
        Assert.notNull(chats.get(PLANNING_CHAT), "Planning chat settings must be set");
        Assert.notEmpty(prices, "Token prices must be set");
        Assert.isTrue(databaseAllowance != null && !databaseAllowance.isNegative() && !databaseAllowance.isZero(),
                "Database allowance must be positive");
        for (var settings : chats.values()) {
            Assert.isTrue(prices.containsKey(settings.model()), "Every chat model must have token prices");
        }
        chats = Map.copyOf(chats);
        prices = Map.copyOf(prices);
    }

    public ChatSettings planning() { return chats.get(PLANNING_CHAT); }

    public record Openai(String apiKey, String baseUrl) {
        public Openai { Assert.hasText(apiKey, "OPENAI_API_KEY must be set"); }

        @Override
        public String toString() { return "Openai[credentials omitted]"; }
    }
}
