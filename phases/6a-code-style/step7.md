# Step 7: brace-and-config-chain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 phase 의 규칙과 이유를 파악하라:

- `docs/CODE_STYLE.md` §2(줄 나누기 — 설정 체인 예외) · §6(중괄호 없는 `if` 는 한 줄에 들어가는 간단한 경우만) — 이 step 은 이 두 규칙만 적용한다. 예시 코드를 꼭 본다
- `docs/ADR.md` 의 ADR-180
- 이전 step 들의 summary — step 3 ~ 6 이 공백만 바꾸며 남긴 것

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. step 3 ~ 6 은 공백만 바꿀 수 있어 중괄호를 달지 못했다. 이 step 은 그 뒤처리다. 바꿀 수 있는 것은 **공백 · 줄바꿈과 `{` · `}` 뿐**이다.

대상 경로: `backend/src/main/java` · `backend/src/test/java` 전체.

### 1. 여러 줄에 걸친 중괄호 없는 `if` 에 중괄호를 단다 — `docs/CODE_STYLE.md` §6

- 조건이나 본문이 두 줄 이상인 중괄호 없는 `if` · `else if` · `else` 를 찾아 중괄호를 단다. 예: `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` 의 `resolve(…)` 안 `if (match.isMatch() …) return null;` · `backend/src/main/java/com/mogum/job/dto/JobReceiveRequestValidator.java` 의 `choice(…)` 안 `else if`. 이 둘 말고도 대상 경로 전체를 찾는다.
- 같은 `if` · `else` 사슬에서 한 갈래에 중괄호를 달면 그 사슬의 모든 갈래에 단다 — 갈래마다 모양이 다르면 어느 문장이 어느 갈래인지 읽기 어렵다.
- 모양: 여는 `{` 는 조건이 끝나는 줄 끝, 본문은 `if` 줄 들여쓰기 + 4칸, 닫는 `}` 는 `if` 줄 들여쓰기. `else` 는 `} else {` 처럼 닫는 중괄호와 같은 줄. 조건의 이어지는 줄은 + 8칸 그대로.
- 조건과 본문이 한 줄에 들어간 `if`(한 줄 `if`)는 건드리지 않는다.
- `for` · `while` 등 다른 문장은 이 step 범위가 아니다.

### 2. 설정 체인의 짝을 한 줄로 잇는다 — `docs/CODE_STYLE.md` §2 예외

- 호출 둘이 뜻 하나인 설정 체인을 짝마다 한 줄로 잇는다 — 대표는 `backend/src/main/java/com/mogum/auth/SecurityConfiguration.java` 의 `authorizeHttpRequests(…)` 안 `.requestMatchers(…)` / `.dispatcherTypeMatchers(…)` / `.anyRequest()` 와 그 뒤 `.permitAll()` · `.authenticated()` · `.denyAll()` · `.access(…)`. step 3 이 호출마다 나눴다.
- 이은 줄이 120자를 넘으면 그 짝의 인자 안에서 쉼표 뒤로 나눈다(+ 8칸). `.access(…)` 처럼 인자가 여러 줄인 람다는 `.requestMatchers("/api/**").access((authentication, context) ->` 까지 한 줄에 두고 람다 본문은 지금처럼 이어지는 줄로 둔다.
- 체인의 첫 짝도 같다 — `requests.dispatcherTypeMatchers(…)` 를 `requests` 다음 줄로 내려 다른 짝과 같은 들여쓰기에 둬도 된다(공백만 바뀐다).
- 테스트 코드에 같은 설정 체인이 있으면 같이 고친다.

### 3. 확인 · summary

- 공백 · `{` · `}` 말고는 바뀌지 않았는지 확인한다 — 바뀐 파일마다 `HEAD` 의 내용과 지금 내용에서 공백 문자 · `{` · `}` 를 모두 지운 두 문자열이 같은지 본다(한 줄짜리 Python 스크립트면 된다). 또 파일마다 더한 `{` 수와 `}` 수가 같아야 한다.
- 아래 git 명령의 출력에서 바뀐 글자 표시는 `{+{+}` · `{+}+}` 뿐이어야 한다. git 이 빈 줄을 엇갈려 짝지어 같은 줄을 지웠다 다시 넣은 것처럼 표시하면(`[-…-]` 와 `{+…+}` 가 공백을 빼면 같은 글자) 실패가 아니다 — 그 위치를 summary 에 적는다.

  ```bash
  git diff -w --word-diff=plain '--word-diff-regex=[^[:space:]]' HEAD -- backend/src/main/java backend/src/test/java
  ```

- `summary` 에 중괄호를 단 `if` 수와 파일 목록, 이은 설정 체인 파일을 적는다.
- TDD 가드(Stop 훅)가 「구현만 바꾸고 테스트 변경이 없다」고 한 번 막으면, 중괄호와 공백만 바꿔 동작이 바뀌지 않았다는 근거를 답하고 다시 끝낸다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build
git diff -w --word-diff=plain '--word-diff-regex=[^[:space:]]' HEAD -- backend/src/main/java backend/src/test/java
```

리뷰어는 두 번째 명령의 `HEAD` 자리에 리뷰 대상 base 커밋을 넣어 실행한다 — 표시는 `{+{+}` · `{+}+}` 뿐이거나, 지운 쪽과 넣은 쪽이 공백을 빼면 같은 글자여야 한다. 다른 글자가 하나라도 바뀌었으면 반려한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다 — build 가 통과하고, 두 번째 명령의 표시가 위 조건을 지켜야 한다.
2. 체크리스트를 확인한다:
   - 대상 경로에 두 줄 이상 걸친 중괄호 없는 `if` · `else` 가 남지 않았는가?
   - 한 줄 `if` 를 건드리지 않았는가?
   - 설정 체인의 짝이 한 줄씩인가 · 120자를 넘는 줄이 새로 생기지 않았는가 · 이어지는 줄이 8칸인가?
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 공백 · `{` · `}` 말고는 어떤 글자도 바꾸지 마라 — 이름 · import · 주석 · 조건식 · 문자열 모두 포함. 이유: 이 step 은 "중괄호와 공백만 바뀌었다" 를 diff 로 증명하는 것이 안전망이다.
- 한 줄 `if` 에 중괄호를 달거나, 중괄호를 달려고 `if` 를 한 줄로 합치지 마라. 이유: 한 줄 `if` 는 허용된 모양이다(CODE_STYLE §6) · 여러 줄로 나뉜 것은 앞 step 이 120자 · 읽기 때문에 나눈 것이다.
- 위 1 · 2 말고 빈 줄 · 줄 나누기를 다시 고치지 마라. 이유: step 3 ~ 6 의 리뷰를 거쳤다.
- `docs/` · `AGENTS.md` 를 고치지 마라. 규칙과 코드가 맞지 않으면 blocked 로 멈춰라.
- `frontend/` · `scripts/` 를 건드리지 마라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
