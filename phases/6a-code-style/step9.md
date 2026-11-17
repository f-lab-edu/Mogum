# Step 9: role-prefixed-names

## 읽어야 할 파일

- `docs/CODE_STYLE.md` §3 — 「범용 타입도 역할을 앞에 붙인다 · 추상 타입은 `Abstract` 를 떼고 본다」 줄
- 이전 step 의 summary(step 8 의 매개변수 이름 대응)

## 작업

이 phase 는 **동작을 바꾸지 않는 코드 스타일 정리**다. 이 step 은 step 8 이 바꾼 `@Bean` 메서드 매개변수 이름 둘을 역할이 드러나게 다시 바꾼다. 메서드 본문에서 쓰는 곳도 함께 바꾼다.

| 파일 · 메서드 | 지금 | 바꾼 뒤 |
|---|---|---|
| `backend/src/main/java/com/mogum/config/RequestLogConfiguration.java` · `requestLogCompletion` | `FilterRegistrationBean<RequestLogFilter> filterRegistrationBean` | `requestLogFilterRegistrationBean` |
| `backend/src/test/java/com/mogum/support/IntegrationTestConfiguration.java` · `FilterFailure.filterFailure` | `AbstractFilterRegistrationBean<?> abstractFilterRegistrationBean` | `securityFilterRegistrationBean` |

- 이름이 길어져 줄이 120자를 넘으면 `docs/CODE_STYLE.md` §2 대로 나눈다. 넘지 않으면 줄 배치를 바꾸지 않는다.
- `summary` 에 바꾼 두 이름과 빌드 결과를 적는다.
- TDD 가드(Stop 훅)가 「구현만 바꾸고 테스트 변경이 없다」고 한 번 막으면, 매개변수 이름만 바꿔 동작이 바뀌지 않았다는 근거를 답하고 다시 끝낸다.

## Acceptance Criteria

```bash
./backend/gradlew -p backend build
git diff HEAD -- backend/src/main/java backend/src/test/java
```

리뷰어는 두 번째 명령의 `HEAD` 자리에 리뷰 대상 base 커밋을 넣어 실행한다 — 바뀐 것은 위 표의 두 매개변수 이름과 그 쓰임뿐이어야 한다.

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 바뀐 것이 두 매개변수 이름과 그 쓰임뿐인지 확인한다.
3. 결과에 따라 `phases/6a-code-style/index.json` 의 해당 step 을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 요약"`
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 위 두 이름 말고 어떤 코드도 바꾸지 마라 — `@Qualifier` 값 · 빈 이름 · 다른 매개변수 포함. 이유: 이 phase 는 동작을 바꾸지 않는 정리이고 이 step 은 이름 둘만 다룬다.
- `docs/` · `AGENTS.md` 를 고치지 마라.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라.
