package com.mogum.generation.plan;

import com.mogum.course.domain.PlannedConcept;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PlanJson {
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private PlanJson() { }

    /** 앞뒤 설명 속 객체는 건너뛰고 계획 JSON 하나만 꺼낸다. */
    static Optional<JsonNode> read(String text) {
        if (blank(text)) return Optional.empty();

        JsonNode result = null;
        for (int start = 0; start < text.length(); start++) {
            char first = text.charAt(start);
            if (first != '{' && first != '[') continue;

            int end = endOfValue(text, start);
            if (end < 0) continue;
            try {
                var node = JSON_MAPPER.readTree(text.substring(start, end + 1));
                if (node.isObject() && (node.has("lessons") || node.has("courses"))) {
                    if (result != null) return Optional.empty();
                    result = node;
                }
            } catch (JacksonException ignored) {
                // 앞뒤 설명에 든 괄호는 건너뛴다. 예외에 섞인 원문은 남기지 않는다.
            }
            start = end;
        }
        return Optional.ofNullable(result);
    }

    static boolean title(JsonNode node) {
        return text(node) && node.asString().codePointCount(0, node.asString().length()) <= 100;
    }

    static boolean text(JsonNode node) {
        return node != null && node.isString() && !blank(node.asString());
    }

    static boolean blank(String value) {
        return value == null || value.codePoints().allMatch(point ->
                Character.isWhitespace(point) || Character.isSpaceChar(point));
    }

    static boolean outline(JsonNode node, boolean course) {
        if (node == null || !node.isObject()
                || !(course ? title(node.path("title")) : text(node.path("title")))
                || !text(node.path("description"))) {
            return false;
        }

        var concepts = node.path("concepts");
        if (!concepts.isArray() || concepts.isEmpty()) return false;
        for (var concept : concepts) {
            if (!concept.isObject() || !text(concept.path("name")) || !text(concept.path("definition"))) {
                return false;
            }
        }
        return true;
    }

    static List<PlannedConcept> concepts(JsonNode outline) {
        var result = new ArrayList<PlannedConcept>();
        for (var concept : outline.path("concepts")) {
            result.add(new PlannedConcept(concept.path("name").asString(), concept.path("definition").asString()));
        }
        return List.copyOf(result);
    }

    private static int endOfValue(String text, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{' || current == '[') {
                depth++;
            } else if (current == '}' || current == ']') {
                if (--depth == 0) return index;
            }
        }
        return -1;
    }
}
