package com.mogum.generation;

import com.mogum.generation.call.domain.LlmCallOutcome;

import java.util.List;

/** HTTP 요청 한 번의 경계. SDK 타입 없이 스텁으로 바꿀 수 있다. */
@FunctionalInterface
public interface ChatProvider {
    Reply send(Request request);

    record Request(ChatSettings settings, List<Message> messages) {
        public Request { messages = List.copyOf(messages); }
    }

    record Message(Role role, String text) { }

    record Usage(Integer inputTokens, Integer cachedInputTokens, Integer outputTokens) { }

    record Reply(String text, LlmCallOutcome outcome, GenerationOutcome.Failure failure, Usage usage) { }

    enum Role {
        /** 서버가 정한 지시 */
        SYSTEM,
        /** 입력 또는 이어 쓰기 요청 */
        USER,
        /** 앞 호출에서 받은 출력 */
        ASSISTANT
    }
}
