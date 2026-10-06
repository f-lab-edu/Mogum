# reference-code — 1단계 실측 하네스에서 가져온 참고 코드

**그대로 컴파일되지 않는다.** 패키지가 `phase1` 이고, 일부는 하네스 전용 클래스(`Recorder` · `Topics` · `Pacer`)에 묶여 있다.
실제 API 로 검증된 **SDK 사용법과 조립 순서**를 옮겨 쓰려고 둔다. 구현이 끝나면 지운다.

| 파일 | 쓸 곳 | 옮길 때 바꿀 것 |
|---|---|---|
| `LlmClient.java` · `EmbeddingClient.java` | `core` 포트의 출발점 | 모델명 대신 `ModelTier` · `JobRef` 추가 · `EmbeddingClient` 는 `Optional<float[]>` 로 ([docs/03 §8](../docs/03-generation.md)) |
| `OpenAiChat.java` | `infra` 채팅 어댑터 | `maxRetries(0)` 확인 · 응답 분류(429 종류 · 5xx · 504)를 정규화된 재시도 결정으로 |
| `OpenAiEmbedding.java` | `infra` 임베딩 어댑터 | 타임아웃 10s · 실패 시 empty |
| `Headers.java` | 한도 헤더(`x-ratelimit-*`) 읽기 | 게이지 `llm.ratelimit.remaining` 로 |
| `JsonText.java` | 출력 정리(코드펜스 · 앞뒤 문장 벗기기) · JSON | — |
| `Prompts.java` | `prompts/vN/*.txt` 읽기 · 자리표시자 채우기 · `limits.txt` | 버전을 설정으로 |
| `Pipeline.java` | 잡 A · 잡 B(①②③) **조립 순서** — 툴 왕복 · 강제 종결 · 이어 쓰기 · 잘린 응답 재요청 금지 | 하네스용 기록 · 예산 · 고정 데이터 제거. 잡 단계 저장 · 리스 갱신은 새로 쓴다 |

검증된 환경 — Java 21 · `com.openai:openai-java:4.75.1`(OkHttp 4.12) · `jackson-databind 2.22.3`.
