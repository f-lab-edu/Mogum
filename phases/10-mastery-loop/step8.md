# Step 8: mastery-prompt-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/03-generation.md` §2(「숙련도가 하는 일」 · 「관련 개념 검색」 표) · §3(강의 자료 — 넣는 것 · 강의 한 줄의 개념을 사전과 맞추는 법) · §5(숙련도 반영 내역) · §7(프롬프트 문구 행)
- `docs/ADR.md` — ADR-031 · ADR-090 · ADR-121
- `docs/TEST_GUIDE.md` 전체 · `docs/CODE_STYLE.md`
- `AGENTS.md` — 금지 15 · 20
- `backend/src/main/java/com/mogum/generation/material/MaterialFormat.java` — `prompt` 의 `lesson.concepts` 만들기
- `backend/src/main/java/com/mogum/generation/plan/PlanPromptSupport.java` — `relatedConcepts`(같은 세 칸을 `LinkedHashMap` 으로 만드는 본보기)
- `backend/src/main/resources/prompts/planning/mastery-well-known.txt` · `mastery-shaky.txt` · `mastery-no-record.txt` · `course.txt`
- `backend/src/main/resources/prompts/material/rules.txt`
- `backend/src/test/java/com/mogum/generation/material/MaterialFormatTest.java` · `backend/src/test/java/com/mogum/generation/plan/CoursePlanFormatTest.java` · `RoadmapPlanFormatTest.java` · `PlanPromptSupportTest.java`
- 판정 지시 문구를 글자 그대로 단언하는 다른 시험이 있으면 함께 본다(`backend/src/test` 에서 `자세히 다룬다` 를 찾는다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

phase 리뷰에서 나온 두 가지를 고친다.

1. 강의 자료 프롬프트의 개념 객체를 `Map.of` 로 만들어, JVM 을 켤 때마다 키 순서가 달라질 수 있다. 같은 강의라도 서버를 다시 켜면 프롬프트 글이 달라져 프롬프트 캐싱과 운영자의 프롬프트 비교에 불리하다.
2. `course.txt` 는 「흔들리는 개념을 다시 짚는 강의」 · 「revisited 는 … 흔들리는 관련 개념」이라고 쓰는데, LLM 이 받는 흔들림 지시는 「자세히 다룬다.」뿐이다. 기록 없음 지시(「처음 배우는 것으로 보고 자세히 다룬다.」)와의 차이로만 짐작해야 해서, 다시 짚는 강의가 덜 나오거나 처음 배우는 개념이 `revisited` 에 섞일 수 있다.

## 작업

### 1. 강의 자료 개념 객체의 키 순서 고정

- `MaterialFormat.prompt` 가 `lesson.concepts` 의 개념마다 만드는 객체를 `LinkedHashMap` 으로 바꿔 `name` → `definition` → `instruction` 순서로 넣는다(`PlanPromptSupport.relatedConcepts` 와 같은 방식). 값 · 키 이름은 그대로다.

### 2. 흔들림 지시 문구와 강의 목록 규칙

- `prompts/planning/mastery-shaky.txt` 의 문구를 정확히 다음 한 줄로 바꾼다: `전에 배웠지만 흔들린다. 자세히 다룬다.`
- `prompts/planning/course.txt` 의 두 문장을 그 문구를 가리키게 바꾼다:
    - 「주제와 관련된 흔들리는 개념을 다시 짚는 강의를 넣을 수 있다. 로드맵 계획에 없는 개념이어도 된다.」 → `지시가 「전에 배웠지만 흔들린다」로 시작하는 관련 개념을 다시 짚는 강의를 넣을 수 있다. 로드맵 계획에 없는 개념이어도 된다.`
    - 「revisited는 다시 짚는 강의에서 다룬 흔들리는 관련 개념이다.」 → `revisited는 지시가 「전에 배웠지만 흔들린다」로 시작하는 관련 개념 가운데 다시 짚는 강의에서 다룬 개념이다.`
- `course.txt` 의 다른 문장 · `roadmap.txt` · `material/rules.txt` · `mastery-well-known.txt` · `mastery-no-record.txt` 는 바꾸지 않는다. 강의 자료는 같은 지시 파일을 읽으므로 문구만 함께 바뀐다.

### 3. 시험

- `MaterialFormatTest` — `lesson.concepts` 의 개념 객체 키가 `name, definition, instruction` 순서다(직렬화한 user 글에서 순서를 확인한다) · 흔들림 개념의 `instruction` 이 새 문구다.
- `CoursePlanFormatTest` · `RoadmapPlanFormatTest`(또는 `PlanPromptSupportTest`) — 흔들림 관련 개념의 `instruction` 이 새 문구다.
- `CoursePlanFormatTest` — 강의 목록 system 규칙에 「전에 배웠지만 흔들린다」를 가리키는 두 문장이 있다.
- 옛 문구(「자세히 다룬다.」만)를 흔들림 지시로 단언하던 기존 시험은 새 문구로 고친다. 기록 없음 · 잘 앎 단언은 그대로다.

### 문서

- 고칠 문서는 없다(03 §7 의 프롬프트 문구 행은 파일 경로만 적는다). 문서를 고치지 마라.

## Acceptance Criteria

```bash
./backend/gradlew -p backend test --tests 'com.mogum.generation.*'
./backend/gradlew -p backend build   # Testcontainers — Docker 필요
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 강의 자료 개념 객체의 키 순서가 고정됐고 값 · 키 이름은 그대로인가?
   - 흔들림 문구와 `course.txt` 두 문장이 작업에 적은 글자 그대로인가? 다른 프롬프트 파일은 그대로인가?
   - 판정은 숫자가 아니라 지시 문구로만 넘기는가(ADR-031)? 응답 · 화면에는 아무것도 더하지 않았는가(ADR-090 · 금지 20)?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD · `spotlessApply`)을 위반하지 않았는가?
3. 결과에 따라 `phases/10-mastery-loop/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바꾼 파일 · 새 흔들림 문구 · 서버 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 잘 앎 · 기록 없음 지시 문구와 `roadmap.txt` · `material/rules.txt` 를 바꾸지 마라. 이유: 이 step 은 흔들림 표식만 고친다. 나머지 문구는 공개 전 품질 점검에서 결과를 보고 다룬다.
- 반영 내역 해석 · 저장, 코스 응답 · 화면, 관련 개념 검색(개수 · 하한), 다시 짚는 강의의 몫(10강 중 최대 2강)을 바꾸지 마라. 이유: 이 phase 의 앞 step 이 끝냈고 계약 문서가 정했다.
- 판정 이름 · 숫자를 프롬프트 데이터에 더하지 마라. 이유: ADR-031 — 판정은 지시 문구로만 넘긴다.
- 리뷰 권고의 정리(벡터 변환 중복 · `instanceof` 뒤 캐스팅 · FSRS 카드 빌더 중복)를 하지 마라. 이유: phase 리뷰에서 하지 않기로 정했다.
- 작업에 적지 않은 운영 로그 줄(`INFO` 이상)을 더하지 마라. 이유: 줄마다 14일 · 500MiB 보관을 나눠 쓴다.
- 계약 문서(규칙 문장 · ADR · `AGENTS.md` 금지 목록 · README)를 고치지 마라. 이유: 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
