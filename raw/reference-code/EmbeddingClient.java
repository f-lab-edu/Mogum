package phase1.port;

import java.util.Map;

/** 07 §2.4. 본 앱은 Optional<float[]> 만 돌려주지만, 1단계는 지연 · 토큰 · 헤더를 재야 해서 결과 레코드로 받는다. */
public interface EmbeddingClient {

    EmbeddingResult embed(String text);

    String model();

    record EmbeddingResult(float[] vector, long tokens, long latencyMs, Map<String, String> rateLimitHeaders) {}

    /** 1단계 전용 — 유사도 비교용 일괄 임베딩. 서비스는 개념 하나씩 부른다(07 §2.4). 순서는 입력과 같다. */
    record BatchResult(java.util.List<float[]> vectors, long tokens, long latencyMs) {}

    BatchResult embedBatch(java.util.List<String> texts);
}
