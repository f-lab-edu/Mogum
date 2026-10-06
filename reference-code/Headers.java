package phase1.adapter;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** 레이트리밋 관련 응답 헤더만 남긴다 (E6). 인증 · 쿠키 헤더는 남기지 않는다. */
final class Headers {
    private Headers() {}

    static Map<String, String> rateLimit(Iterable<String> names, Function<String, String> valueOf) {
        Map<String, String> out = new TreeMap<>();
        for (String n : names) {
            String k = n.toLowerCase();
            if (k.contains("ratelimit") || k.equals("retry-after") || k.endsWith("request-id")) {
                out.put(k, valueOf.apply(n));
            }
        }
        return out;
    }
}
