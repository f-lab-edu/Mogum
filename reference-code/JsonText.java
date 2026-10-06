package phase1;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/** 05 §7 의 "정리" — 코드펜스와 앞뒤 설명을 벗기고 첫 `{` 부터 마지막 `}` 까지를 JSON 으로 읽는다. */
public final class JsonText {
    public static final ObjectMapper JSON = new ObjectMapper();

    private JsonText() {}

    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> object(String text) {
        if (text == null) return Optional.empty();
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(text.substring(a, b + 1), Map.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String write(Object o) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
