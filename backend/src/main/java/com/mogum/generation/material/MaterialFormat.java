package com.mogum.generation.material;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.regex.Pattern;

import com.mogum.course.domain.PlannedConcept;
import com.mogum.generation.plan.PlanPrompt;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 본문은 마크다운 그대로, 구분 줄 다음은 카드 JSON 하나다. 이어 붙인 응답도 같은 형식이다. */
public final class MaterialFormat {
	private static final Pattern SEPARATOR = Pattern.compile("(?m)^<!-- MOGUM_CARDS -->\\r?$");
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	public PlanPrompt prompt(MaterialInput input) {
		var data = new LinkedHashMap<String, Object>();
		data.put("courseTitle", input.courseTitle());
		data.put("topic", input.topic());
		data.put("lesson", input.lesson());
		data.put("lessons", input.lessons());
		data.put("purpose", switch (input.purpose()) {
			case WORK -> resource("planning/purpose-work.txt");
			case INTERVIEW -> resource("planning/purpose-interview.txt");
			case GENERAL -> resource("planning/purpose-general.txt");
			case OTHER -> input.purposeOther();
		});
		if (!blank(input.memo())) {
			data.put("memo", input.memo());
		}
		return new PlanPrompt(resource("material/rules.txt") + "\n\n" + resource("material/output.txt"),
			resource("planning/user.txt").replace("{{data}}", JSON_MAPPER.writeValueAsString(data)));
	}

	public Optional<MaterialContent> parse(String text) {
		if (blank(text)) {
			return Optional.empty();
		}
		var separator = SEPARATOR.matcher(text);
		if (!separator.find()) {
			return Optional.empty();
		}
		String body = text.substring(0, separator.start()).strip();
		int cardsStart = separator.end();
		if (blank(body) || separator.find()) {
			return Optional.empty();
		}
		try {
			var root = JSON_MAPPER.readTree(text.substring(cardsStart));
			if (root == null || !root.isObject()) {
				return Optional.empty();
			}
			var cards = root.path("cards");
			if (!cards.isArray() || cards.isEmpty()) {
				return Optional.empty();
			}
			var result = new ArrayList<MaterialContent.Card>();
			for (var card : cards) {
				if (!text(card.path("question")) || !text(card.path("answer"))) {
					return Optional.empty();
				}
				var concepts = card.path("concepts");
				if (!concepts.isArray() || concepts.isEmpty()) {
					return Optional.empty();
				}
				var parsedConcepts = new ArrayList<PlannedConcept>();
				for (var concept : concepts) {
					if (!text(concept.path("name")) || !text(concept.path("definition"))) {
						return Optional.empty();
					}
					parsedConcepts.add(
						new PlannedConcept(concept.path("name").asString(), concept.path("definition").asString()));
				}
				result.add(new MaterialContent.Card(card.path("question").asString(), card.path("answer").asString(),
					parsedConcepts));
			}
			return Optional.of(new MaterialContent(body, result));
		} catch (JacksonException ignored) {
			// 해석 예외에는 생성 원문이 섞인다. 기록하지 않고 형식 불합격으로만 돌린다.
			return Optional.empty();
		}
	}

	private static boolean text(JsonNode node) {
		return node.isString() && !blank(node.asString());
	}

	private static boolean blank(String value) {
		return value == null
			|| value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point));
	}

	private static String resource(String path) {
		try (var stream = MaterialFormat.class.getResourceAsStream("/prompts/" + path)) {
			if (stream == null) {
				throw new IllegalStateException("Material prompt resource is missing");
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
		} catch (IOException ignored) {
			throw new IllegalStateException("Cannot read material prompt resource");
		}
	}
}
