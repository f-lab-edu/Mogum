package phase1.adapter;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import phase1.port.LlmClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OpenAI Chat Completions. 재시도 0 · reasoning_effort=none (Anthropic 쪽 thinking 끔과 짝 — 20 §3). */
public final class OpenAiChat implements LlmClient {
    private final OpenAIClient client;

    public OpenAiChat(String apiKey, String baseUrl) {
        var b = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0).timeout(Duration.ofSeconds(300));
        if (baseUrl != null) b.baseUrl(baseUrl);
        this.client = b.build();
    }

    @Override public String provider() { return "openai"; }

    public static String reasoningSetting(String model) { return "reasoning_effort=none"; }

    @Override
    public LlmResponse call(LlmRequest req) {
        var p = ChatCompletionCreateParams.builder()
                .model(req.model())
                .maxCompletionTokens(req.maxTokens())
                .reasoningEffort(ReasoningEffort.NONE);
        if (req.system() != null) p.addSystemMessage(req.system());
        for (var t : req.tools()) p.addTool(toTool(t));
        if (req.finalAnswerOnly() && !req.tools().isEmpty()) p.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
        for (var turn : req.turns()) {
            switch (turn) {
                case UserText u -> p.addUserMessage(u.text());
                case AssistantTurn a -> p.addMessage((ChatCompletionMessage) a.providerMessage());
                case ToolResults r -> r.results().forEach(tr -> p.addMessage(
                        ChatCompletionToolMessageParam.builder().toolCallId(tr.toolCallId()).content(tr.content()).build()));
            }
        }

        long t0 = System.nanoTime();
        HttpResponseFor<ChatCompletion> raw = client.chat().completions().withRawResponse().create(p.build());
        ChatCompletion c = raw.parse();
        long latency = (System.nanoTime() - t0) / 1_000_000;

        var choice = c.choices().get(0);
        ChatCompletionMessage msg = choice.message();
        List<ToolCall> calls = new ArrayList<>();
        msg.toolCalls().ifPresent(list -> list.forEach(tc -> tc.function().ifPresent(f ->
                calls.add(new ToolCall(f.id(), f.function().name(), f.function().arguments())))));
        String rawStop = choice.finishReason().toString();
        var usage = c.usage().map(u -> new Usage(u.promptTokens(), u.completionTokens(),
                u.completionTokensDetails().flatMap(d -> d.reasoningTokens()).orElse(0L)));
        var headers = Headers.rateLimit(raw.headers().names(), n -> String.join(",", raw.headers().values(n)));
        return new LlmResponse(stop(rawStop), rawStop, msg.content().orElse(""), calls, usage, latency, headers, msg);
    }

    private static StopReason stop(String s) {
        return switch (s) {
            case "stop" -> StopReason.END_TURN;
            case "length" -> StopReason.MAX_TOKENS;
            case "tool_calls" -> StopReason.TOOL_USE;
            default -> StopReason.OTHER;
        };
    }

    private static ChatCompletionFunctionTool toTool(ToolSpec spec) {
        var params = FunctionParameters.builder();
        for (Map.Entry<String, Object> e : spec.inputSchema().entrySet()) {
            params.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder().name(spec.name()).description(spec.description()).parameters(params.build()).build())
                .build();
    }
}
