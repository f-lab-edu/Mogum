package com.mogum.generation;

import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** 한 시도: 최초 호출과 정한 횟수의 이어 쓰기. 처음부터 다시 시도하는 일은 워커가 한다. */
public final class ChatCall {
    private static final String CONTINUE = "방금 쓴 답이 출력 길이 제한에 걸려 중간에 끊겼다. "
            + "이미 쓴 부분은 반복하지 말고, 끊긴 곳의 바로 다음 글자부터 이어서 끝까지 써라. "
            + "머리말 · 설명 · 코드펜스 없이 이어지는 부분만 출력한다.";

    private final ChatProvider chatProvider;
    private final LlmCallWriter llmCallWriter;
    private final Map<String, TokenPrices> prices;

    public ChatCall(ChatProvider chatProvider, LlmCallWriter llmCallWriter, Map<String, TokenPrices> prices) {
        this.chatProvider = chatProvider;
        this.llmCallWriter = llmCallWriter;
        this.prices = Map.copyOf(prices);
    }

    public <T> ChatResult<T> call(
            JobSnapshot job, ChatSettings settings, String system, String user,
            Function<String, Optional<T>> parser
    ) {
        // 호출자가 연결을 쥔 채 들어오면 유료 호출을 보내지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
        }

        var messages = new ArrayList<ChatProvider.Message>();
        messages.add(new ChatProvider.Message(ChatProvider.Role.SYSTEM, system));
        messages.add(new ChatProvider.Message(ChatProvider.Role.USER, user));
        var text = new StringBuilder();
        for (int continuation = 0; continuation <= settings.maxContinuations(); continuation++) {
            final long callId;
            try {
                callId = llmCallWriter.start(job.id(), job.userId(), job.type(),
                        continuation == 0 ? LlmCallType.CHAT : LlmCallType.CONTINUATION, settings.model());
            } catch (RuntimeException ignored) {
                return new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
            }

            long started = System.nanoTime();
            ChatProvider.Reply reply;
            try {
                reply = chatProvider.send(new ChatProvider.Request(job.id(), job.type(), settings, messages));
            } catch (RuntimeException ignored) {
                reply = new ChatProvider.Reply(null, LlmCallOutcome.PROVIDER_ERROR,
                        GenerationOutcome.Failure.PROVIDER_UNAVAILABLE, null);
            }
            int duration = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
            var outcome = reply.outcome();
            Optional<T> parsed = Optional.empty();
            if (outcome == LlmCallOutcome.SUCCEEDED || outcome == LlmCallOutcome.TRUNCATED) {
                text.append(reply.text());
            }
            if (outcome == LlmCallOutcome.SUCCEEDED) {
                try {
                    parsed = parser.apply(text.toString());
                } catch (RuntimeException ignored) {
                    // 해석 예외에는 출력 원문이 섞일 수 있다. 작업 실패로만 바꾼다.
                }
                if (parsed == null || parsed.isEmpty()) outcome = LlmCallOutcome.INVALID_OUTPUT;
            }

            finish(callId, outcome, reply.usage(), settings.model(), duration);
            if (outcome == LlmCallOutcome.SUCCEEDED) return new ChatResult.Success<>(parsed.orElseThrow());
            if (outcome != LlmCallOutcome.TRUNCATED) {
                return new ChatResult.Failed<>(reply.failure() == null
                        ? GenerationOutcome.Failure.INVALID_OUTPUT : reply.failure());
            }
            if (continuation == settings.maxContinuations()) break;

            messages.add(new ChatProvider.Message(ChatProvider.Role.ASSISTANT, reply.text()));
            messages.add(new ChatProvider.Message(ChatProvider.Role.USER, CONTINUE));
        }
        return new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT);
    }

    private void finish(long id, LlmCallOutcome outcome, ChatProvider.Usage usage, String model, int duration) {
        try {
            llmCallWriter.finish(id, outcome, usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(), prices.get(model).cost(usage), duration);
        } catch (RuntimeException ignored) {
            // 기록 저장소의 실패가 이미 받은 결과까지 버리게 하지는 않는다. 재호출하지 않는다.
        }
    }
}
