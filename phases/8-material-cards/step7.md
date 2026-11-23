# Step 7: material-format-tolerance

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §3(강의 자료와 카드) · §7
- `docs/ADR.md` — ADR-119 · ADR-127 · ADR-163
- `AGENTS.md` — 금지 12 · 13 · 26
- 대상 코드: `backend/src/main/java/com/mogum/generation/material/MaterialFormat.java` · `backend/src/main/resources/prompts/material/output.txt`
- 대상 시험: `backend/src/test/java/com/mogum/generation/material/` 의 `MaterialFormat` 짝 시험

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

브랜치 리뷰에서 나온 수정이다. `MaterialFormat.parse` 는 본문 · 구분 줄(`<!-- MOGUM_CARDS -->`) · 카드 JSON 을 해석한다. 지금은 **구분 줄 끝에 공백(스페이스 · 탭)이 붙거나, 카드 JSON 을 코드펜스(```` ```json ```` … ```` ``` ````)로 감싸기만 해도 형식 불합격**이다. 형식 불합격은 자동 다시 시도로 채팅 호출을 처음부터 다시 하므로(ADR-127) 강의 하나 값(약 80원)을 한 번 더 쓴다. 프롬프트는 이 둘을 금지하지만, LLM 이 자주 하는 사소한 어긋남이라 해석 쪽에서 받아 준다.

## 작업

### 1. `MaterialFormat.parse` 가 받아 줄 것

- **구분 줄의 앞뒤 공백** — 구분 줄이 그 줄에 혼자 있고 앞뒤에 공백 · 탭만 있으면 구분 줄로 본다(`\r` 포함). 본문 안에서 다른 글과 같은 줄에 있는 `<!-- MOGUM_CARDS -->` 는 지금처럼 구분 줄이 아니다.
- **카드 JSON 을 감싼 코드펜스 하나** — 구분 줄 뒤의 글이 앞뒤 공백을 빼고 ```` ``` ```` 또는 ```` ```json ````(대소문자 무관)으로 시작하고 ```` ``` ```` 로 끝나면 그 안쪽만 JSON 으로 읽는다. 펜스 바깥에 다른 글이 있으면 지금처럼 불합격이다.
- 그 밖의 규칙은 그대로 둔다 — 구분 줄은 한 번만 · 본문이 비면 불합격 · JSON 뒤에 덧붙인 글은 불합격(`FAIL_ON_TRAILING_TOKENS`) · 카드 1장 이상 · 카드마다 개념 1개 이상 · 빈 글 불합격 · 수의 범위는 검사하지 않음.
- 이어 쓰기로 이어 붙인 출력에도 같은 규칙이 적용된다(지금처럼 전체 글을 한 번에 해석한다).
- 프롬프트(`output.txt`)의 금지 문장은 그대로 둔다 — 지시는 엄격하게, 해석은 너그럽게.

### 2. 시험 — `MaterialFormat` 짝 시험에 더한다

- 구분 줄 끝 공백 · 탭 · 앞 공백 · `\r\n` 을 받는다.
- ```` ```json ```` · ```` ```JSON ```` · ```` ``` ```` 펜스로 감싼 카드 JSON 을 받고, 펜스 안쪽 내용만 해석한다.
- 펜스 바깥에 글이 있거나, 펜스가 닫히지 않았거나, 펜스가 둘이면 불합격이다.
- 본문 안의 코드 블록(```` ```java ```` 등)과 Mermaid · KaTeX 는 지금처럼 본문에 그대로 남는다 — 본문의 펜스를 카드 JSON 펜스로 착각하지 않는다.
- 다른 글과 같은 줄의 `<!-- MOGUM_CARDS -->` 는 구분 줄이 아니다.

### 3. 문서

- 고칠 문서 문장이 있으면(`docs/03-generation.md` §3 · `docs/07-testing.md` §2 에 형식 검사의 세부를 적은 곳) 받아 주는 두 경우를 한 줄로 더한다. 없으면 고치지 않는다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 받아 주는 것이 구분 줄의 앞뒤 공백과 카드 JSON 을 감싼 펜스 하나뿐인가? 다른 불합격 규칙이 그대로인가?
   - 해석 실패의 예외 메시지 · 원문을 로그에 남기지 않는가(금지 26)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 받아 주게 된 경우 · 더한 시험 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 카드 수 · 개념 수의 범위 검사를 더하지 마라. 이유: 03 §3 — 범위를 넘어도 받는다.
- 본문의 마크다운을 고치거나 다듬지 마라(앞뒤 공백 제거 말고). 이유: 화면이 받은 그대로 그린다(ADR-117).
- 프롬프트 문장을 바꾸지 마라. 이유: 이 step 은 해석만 고친다.
- 다른 생성 처리기(강의 목록 · 로드맵 계획)의 형식 클래스를 고치지 마라. 이유: 이 step 의 범위가 아니다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
