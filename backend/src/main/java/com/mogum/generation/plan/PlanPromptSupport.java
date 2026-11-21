package com.mogum.generation.plan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

final class PlanPromptSupport {
	private static final JsonMapper JSON_MAPPER = new JsonMapper();

	private PlanPromptSupport() {}

	static Map<String, Object> materials(PlanningInput input, boolean includeLevel) {
		var data = new LinkedHashMap<String, Object>();
		data.put("topic", input.topic());
		data.put("purpose", switch (input.purpose()) {
			case WORK -> resource("purpose-work.txt");
			case INTERVIEW -> resource("purpose-interview.txt");
			case GENERAL -> resource("purpose-general.txt");
			case OTHER -> input.purposeOther();
		});
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

	static void optionalText(Map<String, Object> data, String key, String text) {
		if (!PlanJson.blank(text))
			data.put(key, text);
	}

	static PlanPrompt prompt(String rules, String output, Map<String, Object> data) {
		String system = resource("common.txt") + "\n\n" + resource(rules);
		if (output != null)
			system += "\n\n" + resource(output);
		String user = resource("user.txt").replace("{{data}}", JSON_MAPPER.writeValueAsString(data));
		return new PlanPrompt(system, user);
	}

	private static String resource(String name) {
		try (var stream = PlanPromptSupport.class.getResourceAsStream("/prompts/planning/" + name)) {
			if (stream == null)
				throw new IllegalStateException("Planning prompt resource is missing");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
		} catch (IOException ignored) {
			throw new IllegalStateException("Cannot read planning prompt resource");
		}
	}
}
