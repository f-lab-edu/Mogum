# Step 7: enum-comments

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` 「작업 방식」의 **Java enum 주석** 줄 — 이 step 이 따르는 규칙
- `docs/02-domain-data.md` §8 의 값 표 — **`users.status`** · **`credit_entries.type`** (이름 · 뜻 · 언제)
- `docs/05-api.md` §1.2(오류 — 묶음 이름과 서버가 정한 문구 · `ACCOUNT_NOT_APPROVED` 줄) · §1.1(인증)
- `docs/GLOSSARY.md` — 「신청 · 승인 · 거절됨」 줄
- 주석을 달 enum:
  - `backend/src/main/java/com/mogum/account/domain/UserStatus.java`
  - `backend/src/main/java/com/mogum/account/domain/CreditEntryType.java`
  - `backend/src/main/java/com/mogum/account/service/LoginResult.java` 의 `RejectionReason`
  - `backend/src/main/java/com/mogum/common/api/ErrorCode.java`
  - `backend/src/main/java/com/mogum/common/api/FieldViolation.java` 의 `Reason`
- 표에 없는 값의 뜻을 확인할 곳: 그 값을 쓰는 코드(예: `RejectionReason` → `account/service/LoginService.java` · `auth/OAuthLoginHandler.java`, `FieldViolation.Reason` → `common/api/ApiExceptionHandler.java`, `ErrorCode` 각 값 → 그 값을 던지거나 쓰는 보안 핸들러 · 필터 · 예외 핸들러)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

이 phase(`4-account`)는 step 0 ~ 6 으로 끝났고, 지금 PR 리뷰 중이다. 사용자가 리뷰하면서 enum 상수가 무엇인지 코드에서 바로 알 수 있게 해 달라고 했다. 그래서 `AGENTS.md` 작업 방식에 규칙을 더했다. 규칙은 「Java enum 은 상수마다 한국어 Javadoc 한 줄로 그 값이 무엇인지 적는다」이다. 이 step 은 지금 있는 enum 을 그 규칙에 맞춘다.

## 작업

1. 위 5개 enum 의 **상수마다** 바로 위에 한국어 Javadoc 한 줄(`/** … */`)을 단다.
   - 값 표가 있는 enum(`UserStatus` · `CreditEntryType`)은 02 §8 표의 이름과 뜻 · 언제를 그대로 옮긴다. 예: `/** 신청 — 신청하고 승인을 기다린다. 거절을 풀어도 여기로 돌아온다 */`.
   - 표에 없는 값(`ErrorCode` 대부분 · `RejectionReason` · `FieldViolation.Reason`)은 그 값을 쓰는 코드를 읽고, **언제 나가는지**를 확인한 사실만 적는다. 예: 어떤 요청에서 · 어떤 HTTP 상태로 나가는지. 코드에서 확인하지 못한 뜻은 지어내지 마라.
   - `ErrorCode` 의 문구 인자(`"로그인이 필요합니다"` 등)는 사용자에게 보이는 문구다. 주석은 문구를 되풀이하지 말고, 언제 나가는지를 적는다.
   - 여러 상수가 한 줄에 있으면(`REFILL, CONSUME, RESTORE`) 상수마다 주석을 달 수 있게 줄을 나눈다.
2. enum 타입 자체에 이미 설명이 없으면, 타입 위에도 한 줄 Javadoc 을 단다. 예: `/** 계정 상태 — 신청 전(NOT_APPLIED)은 DB 상태가 아니라 여기 없다 */`. 근거는 02 §8 의 표 제목과 GLOSSARY 다.
3. 주석과 줄 나눔 말고는 아무것도 바꾸지 않는다. 상수 이름 · 순서 · 인자 · 메서드 · import 는 그대로 둔다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # 컴파일 · ArchUnit · 테스트 통과 (Testcontainers — Docker 필요)
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 바뀐 것이 위 5개 파일의 주석 · 줄 나눔뿐인가? `git diff` 에서 주석이 아닌 토큰(이름 · 순서 · 인자)이 바뀌지 않았는가?
   - 5개 enum 의 상수가 하나도 빠짐없이 주석을 가졌는가?
   - 02 §8 표가 있는 값은 표와 같은 말을 썼는가? 표에 없는 값의 설명은 코드에서 확인한 사실인가?
   - `AGENTS.md` 금지 목록을 위반하지 않았는가?
3. 결과에 따라 `phases/4-account/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"` — 주석을 단 enum · 상수 수 · 표에 없어 코드에서 확인한 값과 그 근거 위치
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (코드의 쓰임이 문서와 어긋남 · 쓰는 곳이 없어 뜻을 확인할 수 없는 값 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 옛 `curriculum` 패키지의 enum(`CurriculumStatus` · `LessonStatus`)에 주석을 달지 마라. 이유: 새 설계의 TASK 5 에서 모델과 함께 지운다(ADR-148).
- 상수 이름 · 순서 · 문구 · 동작을 바꾸지 마라. 쓰이지 않는 상수를 발견해도 지우지 말고 summary 에 적어라. 이유: 이 step 은 주석만 단다. API · DB 가 이 이름을 그대로 쓴다(02 §8).
- `docs/` 를 고치지 마라. 코드와 문서가 어긋나면 `blocked` 로 멈춰라. 이유: 문서가 맞는지는 사용자가 정한다.
- 영어 주석이나 여러 줄 주석으로 쓰지 마라. 이유: `AGENTS.md` 규칙은 한국어 한 줄이다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
