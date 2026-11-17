# Step 4: layout-test-a

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 phase 의 규칙과 이유를 파악하라:

- `docs/CODE_STYLE.md` §1(빈 줄 — 문맥 단위) · §2(줄 길이 · 줄 나누기) — 이 step 은 이 두 절만 적용한다. 예시 코드를 꼭 본다
- `docs/ADR.md` 의 ADR-180
- `docs/TEST_GUIDE.md` §3(본문 — given/when/then)
- 이전 step 들의 summary

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. 이 step 은 아래 경로의 Java 파일에서 **공백 · 줄바꿈만** 바꾼다 — 빈 줄을 넣고 빼기, 줄 나누기 · 잇기, 들여쓰기. 글자(공백이 아닌 문자)는 하나도 바꾸지 않는다.

대상 경로(이 step 범위):

- `backend/src/test/java/com/mogum/account/`
- `backend/src/test/java/com/mogum/auth/`
- `backend/src/test/java/com/mogum/learning/`
- backend/src/test/java/com/mogum/*.java (하위 디렉터리 제외)

### 1. 빈 줄 — `docs/CODE_STYLE.md` §1

- 클래스: 선언 줄 바로 다음은 빈 줄 없음 · static 상수 묶음 / 인스턴스 필드 묶음 / 생성자 / 메서드 / 중첩 타입 사이에 빈 줄 하나.
- 메서드 본문: 하는 일이 바뀌는 곳(준비 → 검사 → 처리 → 기록 · 결과)에 빈 줄 하나. 이어진 가드 묶음 뒤 · 다른 일을 하는 여러 줄 블록 앞뒤에 빈 줄. 문장 세 줄 이하 메서드는 나누지 않는다.
- 빈 줄 두 줄 이상 · 블록 여는 줄 바로 다음 · 닫는 줄 바로 앞의 빈 줄은 없앤다.
- 주석은 설명하는 코드 바로 위, 그 주석 앞에 빈 줄(블록 첫 줄이면 없음).
- 테스트 본문은 `// given` · `// when` · `// then` 주석으로 이미 나뉘어 있다 — 주석 앞 빈 줄을 맞추고, 한 단계 안이 길면 그 안에서도 위 규칙대로 나눈다(`docs/TEST_GUIDE.md` §3).

기계적으로 넣지 말고 **사람이 읽는 문맥 단위**로 판단한다 — 같은 일을 하는 두세 줄은 붙여 둔다.

### 2. 줄 길이 · 줄 나누기 — `docs/CODE_STYLE.md` §2

- 120자(들여쓰기 포함 · `package` · `import` 줄 제외)를 넘는 줄을 나눈다.
- 이어지는 줄은 앞줄 들여쓰기 + 8칸. 여는 괄호 · 앞 인자 위치에 맞춘 기존 이어쓰기도 8칸으로 바꾼다.
- 연산자(`&&` · `||` · `+` · `?` · `:`)는 앞에서 끊어 다음 줄 맨 앞에 · 메서드 체인은 `.` 앞에서 끊고 끊으면 한 줄에 호출 하나 · 인자는 쉼표 뒤에서 의미가 묶이는 것끼리.
- 예외 — 호출 둘이 뜻 하나인 설정 체인(Spring Security 의 `.requestMatchers(…).permitAll()` 처럼 대상과 규칙이 짝인 것)은 짝마다 한 줄에 둔다(`docs/CODE_STYLE.md` §2). 이미 나뉘어 있으면 잇는다.
- 중괄호 없는 `if` 의 조건이나 본문을 여러 줄로 나눠야 하면 나누되 중괄호는 달지 마라 — 글자 변경이라 step 7 이 단다(`docs/CODE_STYLE.md` §6).
- 120자 안이지만 한 줄에 여러 의미가 붙어 읽기 어려운 줄(긴 조건 · 긴 체인)도 같은 방식으로 나눠도 된다.
- **문자열 리터럴 안을 나눠야만 120자를 지킬 수 있는 줄은 그대로 둔다** — 글자를 바꾸게 된다(`"a" + "b"` 로 쪼개기 금지).

### 3. 확인 · summary

- 공백이 아닌 글자가 하나도 바뀌지 않았는지 두 가지로 확인한다.
  1. 공백을 모두 지운 비교 — 대상 파일마다 `HEAD` 의 내용과 지금 내용에서 공백 문자를 모두 지운 두 문자열이 같은지 본다(한 줄짜리 Python 스크립트면 된다). 하나라도 다르면 고친다.
  2. 아래 git 명령 — 바뀐 줄의 내용은 그대로 보이지만 바뀐 글자 표시(`[-…-]` · `{+…+}`)는 붙지 않는다. 다만 git 이 빈 줄을 문맥으로 엇갈려 짝지으면 **같은 줄을 지웠다 다시 넣은 것처럼 표시가 남을 수 있다**(이 phase 의 step 4 첫 실행에서 실제로 났다). 1 이 같으면 이것은 실패가 아니다 — 남은 표시마다 `[-…-]` 쪽과 `{+…+}` 쪽이 공백을 빼면 같은 글자인지 확인하고 그 위치(`파일:메서드`)를 summary 에 적는다. 이 표시를 없애려고 빈 줄 · 줄 나누기를 규칙과 다르게 두지 마라.

  ```bash
  git diff -w --word-diff=plain '--word-diff-regex=[^[:space:]]' HEAD -- backend/src/test/java/com/mogum/account backend/src/test/java/com/mogum/auth backend/src/test/java/com/mogum/learning ':(glob)backend/src/test/java/com/mogum/*.java'
  ```

- 120자를 넘는 줄이 남았으면 `파일:줄 — 이유(문자열 리터럴 등)` 로 모두 `summary` 에 적는다. 남은 줄이 없으면 「120자 넘는 줄 없음」으로 적는다.
- TDD 가드(Stop 훅)가 「구현만 바꾸고 테스트 변경이 없다」고 한 번 막으면, 공백 · 줄바꿈만 바꿔 동작이 바뀌지 않았다는 근거를 답하고 다시 끝낸다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build
git diff -w --word-diff=plain '--word-diff-regex=[^[:space:]]' HEAD -- backend/src/test/java/com/mogum/account backend/src/test/java/com/mogum/auth backend/src/test/java/com/mogum/learning ':(glob)backend/src/test/java/com/mogum/*.java'
```

리뷰어는 두 번째 명령의 `HEAD` 자리에 리뷰 대상 base 커밋을 넣어 실행한다 — 표시가 남으면 지운 쪽(`[-…-]`)과 넣은 쪽(`{+…+}`)이 공백을 빼면 같은 글자인지 본다(git 이 빈 줄을 엇갈려 짝지으면 같은 줄을 지웠다 다시 넣은 것으로 표시된다 — summary 에 위치가 적혀 있다). 다른 글자가 하나라도 있으면 반려한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다 — build 가 통과하고, 두 번째 명령 출력에 바뀐 글자 표시가 없거나, 남은 표시는 지운 쪽과 넣은 쪽이 공백을 빼면 같아야 한다(위 3 의 1 · 2).
2. 체크리스트를 확인한다:
   - 메서드 · 클래스가 문맥 단위로 나뉘었는가(기계적으로 줄마다 넣지 않았는가)?
   - 120자를 넘는 줄이 summary 에 적은 것 말고는 없는가?
   - 이어지는 줄이 8칸인가?
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약 — 남은 120자 초과 줄 목록 포함"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 공백이 아닌 글자를 하나도 바꾸지 마라 — 이름 · import · 주석 문구 · 중괄호 추가 · 문자열 쪼개기 · 괄호 추가 모두 포함. 이유: 이 step 은 "공백만 바뀌었다" 를 diff 로 증명하는 것이 안전망이다. 이름이 이상해 보여도 이 step 에서 고치지 않는다.
- 대상 경로 밖 파일을 고치지 마라. 이유: 다른 경로는 다른 step 범위다.
- 중괄호 없는 한 줄 `if` 를 중괄호 블록으로 바꾸지 마라. 이유: 허용한 스타일이다(CODE_STYLE §6) · 글자가 바뀐다.
- `docs/` · `AGENTS.md` 를 고치지 마라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
