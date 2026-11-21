# Step 12: parameter-wrapping

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/CODE_STYLE.md` 전체 — **§2 「선언의 매개변수 목록이 한 줄에 다 들어가지 않으면 괄호를 따로 연다」와 그 아래 예(`JobReceiveRequest` · `JobRunner`)가 이 step 의 계약이다**
- `docs/README.md` 「정한 것」 2026-11-18 의 코드 스타일 줄
- `phases/HARNESS-BACKLOG.md` S21 — 공백만 바꾼 변경을 어떻게 검증하나
- `AGENTS.md` — 작업 방식(TDD — 이 step 은 동작을 바꾸지 않는다)
- `backend/src/main/java/com/mogum/job/dto/JobReceiveRequest.java` — 사용자가 고른 본보기

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

코드 스타일 규칙 하나를 새로 정했다(CODE_STYLE §2). 선언(메서드 · 생성자 · record 칸 · `@Bean` 메서드)의 매개변수 목록이 한 줄에 들어가지 않으면 `(` 바로 뒤에서 줄을 바꾸고, 매개변수 줄은 선언 줄 + 8칸, 닫는 `)` 는 선언이 시작한 열에 두고 `{` · `throws …` · `;` 를 같은 줄에 둔다. 이 step 은 `backend/` 의 지금 코드를 이 규칙에 맞춘다. **동작을 바꾸지 않는 줄 배치 정리다.**

**규칙 문장은 이 step 전에 정해 커밋했다. 코드를 규칙에 맞추고, 규칙이 어떤 경우를 정하지 않아 판단이 갈리면 `blocked` 로 멈춰 그 경우를 적어라. 규칙 문장을 고치지 마라.**

## 작업

### 1. 대상

- `backend/src/main/java` 와 `backend/src/test/java` 의 Java 선언 가운데, **매개변수 목록이 지금 여러 줄에 걸친 것** — 메서드 · 생성자 · record 칸 · `@Bean` 메서드 · 인터페이스 · 추상 메서드 · 테스트 클래스 안의 메서드 모두. 어림잡아 57파일 · 102곳이다(Claude 가 세어 본 값 — 정확한 수는 네가 센다).
- 한 줄로 합쳐 120자(들여쓰기 포함) 안에 들어가는 선언도 그대로 여러 줄 규칙으로 바꾸지 말고 **한 줄로 둘지 먼저 본다** — 규칙은 「한 줄에 들어가지 않으면」이다. 한 줄에 들어가면 한 줄로 합친다.
- 호출 인자(`new X(…)` · `method(…)`) · 람다 · 애노테이션 인자 · 메서드 체인은 대상이 아니다 — 지금 줄 배치를 그대로 둔다.

### 2. 모양

```java
public record JobReceiveRequest(
        String type, Long courseId, Long roadmapId, Long lessonId,
        String topic, String level, String levelOther, String purpose,
        String purposeOther, String memo, String feedback
) {
```

- 매개변수를 한 줄에 몇 개 둘지는 **지금 코드가 묶어 둔 그대로** 둔다 — 지금 첫 줄에 있던 매개변수들은 새 첫 매개변수 줄로 내려간다. 120자를 넘게 되면 의미가 묶이는 것끼리 다시 나눈다.
- 매개변수의 애노테이션(`@Valid @RequestBody`)은 그 매개변수와 같은 줄에 둔다. 매개변수 하나가 애노테이션과 함께 120자를 넘으면 CODE_STYLE §2 대로 애노테이션을 바로 위 줄에 같은 들여쓰기로 둔다(첫 실행이 `IntegrationTestConfiguration.java:579` 의 `filterFailure` 에서 이 경우로 `blocked` 였고, 규칙을 더했다).
- 매개변수 목록 뒤의 빈 줄 · 본문 첫 줄은 CODE_STYLE §1 을 그대로 따른다 — 이 step 은 본문의 빈 줄을 바꾸지 않는다.

### 3. 검증 — 공백만 바뀌었다

- **기준은 파일마다 공백(스페이스 · 탭 · 줄바꿈)을 모두 지운 두 문자열이 같은지다**(HARNESS-BACKLOG S21). `git diff -w --word-diff` 의 표시가 남는 것은 실패가 아니다 — git 이 줄 바꿈을 엇갈려 짝지으면 같은 글자도 지웠다 다시 넣은 것처럼 보인다. 표시를 없애려고 규칙과 다른 줄 배치를 시도하지 마라.
- 비교는 저장소에 남기지 않는 임시 스크립트로 하고, 결과(비교한 파일 수 · 모두 같음)를 summary 에 적는다.
- 서버 전체 테스트가 그대로 통과한다(테스트 수가 바뀌지 않는다).

## Acceptance Criteria

```bash
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 바뀐 줄이 모두 선언의 매개변수 목록인가? 호출 인자 · 람다 · 체인 · 본문 빈 줄을 바꾸지 않았는가?
   - 닫는 `)` 가 선언이 시작한 열에 있고 `{` · `throws` · `;` 가 같은 줄인가? 매개변수 줄이 선언 줄 + 8칸인가? 120자를 넘는 줄이 새로 생기지 않았는가?
   - 파일마다 공백을 지운 내용이 바꾸기 전과 같은가(summary 의 비교 결과)?
3. 결과에 따라 `phases/7-course-planning/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 파일 수 · 선언 수 · 한 줄로 합친 수 · 공백 제거 비교 결과 · 서버 테스트 수(전후 같음)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (규칙이 정하지 않은 경우 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 이름 · 순서 · 타입 · 애노테이션 · 주석의 글자를 바꾸지 마라. 이유: 줄 배치만 바꾸는 step 이다 — 공백 제거 비교가 그것을 확인한다.
- 매개변수 목록 밖(호출 인자 · 람다 · 체인 · 본문 빈 줄 · import)의 줄 배치를 바꾸지 마라. 이유: 이번 규칙의 대상이 아니다(CODE_STYLE §2).
- 화면 코드(`frontend/`)와 문서(`docs/`)를 고치지 마라. 이유: 규칙은 백엔드 Java 만이고, 문서는 이 step 전에 정했다.
- 규칙 문장(CODE_STYLE §2)을 고치지 마라. 이유: 이 step 전에 정해 커밋했다. 판단이 갈리면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
