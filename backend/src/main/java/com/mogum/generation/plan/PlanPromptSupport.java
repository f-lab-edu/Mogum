package com.mogum.generation.plan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mogum.card.service.ConceptLookupService.RelatedConcept;

import tools.jackson.databind.json.JsonMapper;

final class PlanPromptSupport {
	private static final JsonMapper JSON_MAPPER = new JsonMapper();

	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private PlanPromptSupport() {}
	// @checkstyle:on

	static Map<String, Object> materials(PlanningInput input, boolean includeLevel) {
		var data = new LinkedHashMap<String, Object>();
		data.put("topic", input.topic());
		data.put("purpose", purposeText(input));
		if (includeLevel) {
			data.put("level", switch (input.level()) {
				case BEGINNER -> resource("level-beginner.txt");
				case BASICS -> resource("level-basics.txt");
				case FAMILIAR -> resource("level-familiar.txt");
				case OTHER -> input.levelOther();
			});
		}
		optionalText(data, "memo", input.memo());
		return data;
	}

	static void relatedConcepts(Map<String, Object> data, List<RelatedConcept> concepts) {
		if (!concepts.isEmpty()) {
			data.put("relatedConcepts", concepts.stream().map(concept -> {
				var value = new LinkedHashMap<String, Object>();
				value.put("name", concept.name());
				value.put("definition", concept.definition());
				value.put("instruction", PlanPromptSupport.resource(switch (concept.judgment()) {
					case WELL_KNOWN -> "mastery-well-known.txt";
					case SHAKY -> "mastery-shaky.txt";
					case NO_RECORD -> "mastery-no-record.txt";
				}));
				return value;
			}).toList());
		}
	}

	static String topicPurposeQuery(PlanningInput input) {
		return input.topic() + "\n" + purposeText(input);
	}

	private static String purposeText(PlanningInput input) {
		return switch (input.purpose()) {
			case WORK -> resource("purpose-work.txt");
			case INTERVIEW -> resource("purpose-interview.txt");
			case GENERAL -> resource("purpose-general.txt");
			case OTHER -> input.purposeOther();
		};
	}

	static void optionalText(Map<String, Object> data, String key, String text) {
		if (!PlanJson.blank(text)) {
			data.put(key, text);
		}
	}

	static PlanPrompt prompt(String rules, String output, Map<String, Object> data) {
		String system = resource("common.txt") + "\n\n" + resource(rules);
		if (output != null) {
			system += "\n\n" + resource(output);
		}
		String user = resource("user.txt").replace("{{data}}", JSON_MAPPER.writeValueAsString(data));
		return new PlanPrompt(system, user);
	}

	static String resource(String name) {
		try (var stream = PlanPromptSupport.class.getResourceAsStream("/prompts/planning/" + name)) {
			if (stream == null) {
				throw new IllegalStateException("Planning prompt resource is missing");
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
		} catch (IOException ignored) {
			throw new IllegalStateException("Cannot read planning prompt resource");
		}
	}
}
