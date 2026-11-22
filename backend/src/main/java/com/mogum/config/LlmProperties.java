package com.mogum.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import com.mogum.generation.ChatSettings;
import com.mogum.generation.TokenPrices;

@ConfigurationProperties("app.llm")
public record LlmProperties(Openai openai, Map<String, ChatSettings> chats, Embedding embedding,
	Map<String, TokenPrices> prices,
	Duration databaseAllowance) {
	private static final String PLANNING_CHAT = "planning";
	private static final String MATERIAL_CHAT = "material";

	public LlmProperties {
		Assert.notNull(openai, "OpenAI settings must be set");
		Assert.notEmpty(chats, "Chat settings must be set");
		Assert.notNull(chats.get(PLANNING_CHAT), "Planning chat settings must be set");
		Assert.notNull(chats.get(MATERIAL_CHAT), "Material chat settings must be set");
		Assert.notNull(embedding, "Embedding settings must be set");
		Assert.notEmpty(prices, "Token prices must be set");
		Assert.isTrue(databaseAllowance != null && !databaseAllowance.isNegative() && !databaseAllowance.isZero(),
			"Database allowance must be positive");
		for (var settings : chats.values()) {
			Assert.isTrue(prices.containsKey(settings.model()), "Every chat model must have token prices");
		}
		Assert.isTrue(prices.containsKey(embedding.model()), "Embedding model must have token prices");
		chats = Map.copyOf(chats);
		prices = Map.copyOf(prices);
	}

	public ChatSettings planning() {
		return chats.get(PLANNING_CHAT);
	}

	public ChatSettings material() {
		return chats.get(MATERIAL_CHAT);
	}

	public record Embedding(String model, Duration timeout) {
		public Embedding {
			Assert.hasText(model, "Embedding model must be set");
			Assert.isTrue(timeout != null && !timeout.isNegative() && !timeout.isZero(),
				"Embedding timeout must be positive");
		}
	}

	public record Openai(String apiKey, String baseUrl) {
		public Openai {
			Assert.hasText(apiKey, "OPENAI_API_KEY must be set");
		}

		@Override
		public String toString() {
			return "Openai[credentials omitted]";
		}
	}
}
