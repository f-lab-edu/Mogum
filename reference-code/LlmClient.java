package phase1.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 07 §2.1 의 포트를 1단계용으로 줄인 모양. 1턴 단위다 — 루프는 호출부가 소유한다.
 * 본 앱과 다른 점: 모델을 ModelTier 가 아니라 모델명으로 받는다(비교가 목적이라서), userId · JobRef 가 없다.
 */
public interface LlmClient {

    LlmResponse call(LlmRequest request);

    String provider();

    record LlmRequest(
            String model,
            String system,
            List<Turn> turns,
            List<ToolSpec> tools,     // 빈 리스트 = 툴 없음. 대화에 이미 툴 호출이 있으면 정의를 지울 수 없어 finalAnswerOnly 로 막는다
            int maxTokens,
            Duration timeout,
            boolean finalAnswerOnly) {   // 강제 종결 — 툴 정의는 두되 부르지 못하게 (tool_choice=none)

        public LlmRequest(String model, String system, List<Turn> turns, List<ToolSpec> tools, int maxTokens, Duration timeout) {
            this(model, system, turns, tools, maxTokens, timeout, false);
        }
    }

    /** 누적 대화. 어시스턴트 턴은 프로바이더 원본 객체를 그대로 들고 다닌다 — 왕복 변환에서 정보가 새지 않게. */
    sealed interface Turn permits UserText, AssistantTurn, ToolResults {}
    record UserText(String text) implements Turn {}
    record AssistantTurn(Object providerMessage) implements Turn {}
    record ToolResults(List<ToolResult> results) implements Turn {}

    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}
    record ToolCall(String id, String name, String argumentsJson) {}
    record ToolResult(String toolCallId, String content) {}

    enum StopReason { END_TURN, MAX_TOKENS, TOOL_USE, OTHER }

    record Usage(long inputTokens, long outputTokens, long reasoningTokens) {}

    record LlmResponse(
            StopReason stopReason,
            String rawStopReason,              // OTHER 일 때 원인을 잃지 않게 (refusal 등)
            String text,
            List<ToolCall> toolCalls,
            Optional<Usage> usage,             // 없을 수 있다 — 07 §5
            long latencyMs,
            Map<String, String> rateLimitHeaders,
            Object providerMessage) {}         // 다음 턴에 AssistantTurn 으로 되돌려 넣는다
}
