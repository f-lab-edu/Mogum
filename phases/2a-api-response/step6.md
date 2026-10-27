# Step 6: field-errors-client

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `AGENTS.md` — 금지 목록 29, 작업 방식(TDD 짝 테스트 규칙 · "바꾸면 깨지는 계약은 넓힐 수 있는 모양으로 두고, 후속 기능은 미리 만들지 않는다")
- `docs/05-api.md` §1 「응답 형식」(모르는 필드 · enum 값 처리), §1.2 「필드」 · 「입력 검증」 항목 · 표의 `FIELD_INVALID` 행
- `docs/06-screens.md` §15.1 표의 `FIELD_INVALID` 행 — 이 step 에서 화면으로 만들지는 않는다(금지사항)
- `docs/ADR.md` ADR-074 — 「검증」의 「화면 API 클라이언트」 항목
- `backend/src/main/java/com/mogum/common/FieldViolation.java` — step 5 가 만든 원소 모양(`field` · `reason`)
- `frontend/src/api/client.ts` · `frontend/src/api/client.test.ts`

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

step 5 가 입력 검증 오류를 `400 FIELD_INVALID` + `fields: [{ "field": "topic", "reason": "TOO_LONG" }]` 로 바꿨다(이전: `STRUCTURAL_LIMIT` + `fields: ["topic"]`). 화면 API 클라이언트가 새 모양을 받게 맞춘다. 아직 `fields` 를 쓰는 화면은 없다.

## 작업

1. `frontend/src/api/client.ts`
   - `ApiErrorCode` 유니온의 `'STRUCTURAL_LIMIT'` 를 `'FIELD_INVALID'` 로 바꾼다. 모르는 code 를 보존하는 `(string & Record<never, never>)` 는 그대로 둔다.
   - 원소 타입을 둔다:
     ```ts
     export type FieldViolationReason = 'TOO_LONG' | 'REQUIRED' | (string & Record<never, never>)
     export interface FieldViolation { field: string; reason: FieldViolationReason }
     ```
     `reason` 은 모르는 값도 문자열 그대로 보존한다 — 05 §1 · §1.2: 서버가 `reason` 값을 더해도 화면이 깨지지 않아야 하고, 화면은 모르는 `reason` 을 일반 문구로 보여 준다.
   - `ApiError.fields` 를 `FieldViolation[] | undefined` 로 바꾼다.
   - 파싱: `fields` 가 배열이고 **모든** 원소가 `field` · `reason` 이 문자열인 객체일 때만 받는다. 하나라도 아니면(이전 형식인 문자열 배열 포함) `fields` 전체를 `undefined` 로 둔다. 원소의 다른 키는 무시하고 `field` · `reason` 만 옮긴다.
2. `frontend/src/api/client.test.ts` — 테스트를 먼저 고친다:
   - 올바른 원소 목록 보존(모르는 `reason` 문자열 포함), 원소의 다른 키 무시
   - 문자열 배열 · `field` 나 `reason` 이 문자열이 아닌 원소 · 객체가 아닌 원소가 하나라도 있으면 `fields` 가 `undefined`
   - `FIELD_INVALID` code 보존, 기존 `retryAfterSeconds` · 모르는 code 보존 테스트 유지
3. `docs/ADR.md` ADR-074 「검증」의 「화면 API 클라이언트」 항목에서 `fields` 에 대한 문장을 이 step 의 결과로 고치고 화면 테스트 수를 갱신한다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `grep -rn STRUCTURAL_LIMIT frontend/src` 의 결과가 없는가?
   - 모르는 `reason` · 모르는 code 를 버리지 않고 보존하는가?
   - 화면이 서버 `message` 를 띄우지 않는 기존 동작이 그대로인가(ADR-074)?
   - `AGENTS.md` 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/2a-api-response/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 어긋남 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 칸 아래 오류 문구를 보여 주는 화면 · 컴포넌트 · 문구 매핑 도우미를 만들지 마라. 이유: 입력 폼(U2 · U10 · U3)은 `6-curriculum-gen` 에서 만든다(`docs/SCHEDULE.md` §4). 06 §15.1 의 문구는 그때 쓴다.
- `FieldViolationReason` 에 `'TOO_LONG'` · `'REQUIRED'` 외의 알려진 값을 더하지 마라. 이유: 서버의 `reason` 은 지금 이 둘뿐이다(05 §1.2). 새 값은 그 제약을 쓰는 TASK 가 더한다.
- 조회 재시도 · 로그인 이동 등 다른 오류 처리를 바꾸지 마라. 이유: 이 step 은 `fields` 모양과 code 이름만 바꾼다.
- `backend/` 를 고치지 마라. 이유: step 5 에서 끝났다.
- ADR-074 「검증」의 「화면 API 클라이언트」 항목 말고는 문서를 고치지 마라. 이유: 결정 문구는 이미 고쳐 두었고, 브랜치 리뷰 결과는 리뷰 때 고친다. 문서와 코드가 맞지 않으면 `blocked` 로 멈춘다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
