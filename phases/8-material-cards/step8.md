# Step 8: lesson-screen-fixes

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/06-screens.md` §8(로드맵 화면 — 다 마침) · §9 · §10
- `docs/02-domain-data.md` §2.3(마지막 코스를 다 마치면 로드맵도 다 마침 — ADR-176)
- `docs/UI_GUIDE.md` §12(강의 자료 본문) · §13(강의 화면 · 축하 화면)
- `AGENTS.md` — 작업 방식(TDD — `frontend/src/…/foo.tsx` 의 짝은 같은 폴더의 `foo.test.tsx`)
- 대상 코드: `frontend/src/components/MaterialBody.tsx` · `frontend/src/api/useLesson.ts` · `frontend/src/api/useRoadmap.ts`(`roadmapKey` · `roadmapsKey`) · `frontend/src/api/useCourse.ts`(`coursesKey`)
- 대상 시험: `frontend/src/components/MaterialBody.test.tsx` · `frontend/src/pages/LessonPage.test.tsx`(또는 `useLesson` 의 짝 시험)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

브랜치 리뷰에서 나온 화면 수정 둘이다.

1. `MaterialBody` 의 Mermaid 그림 id 를 `crypto.randomUUID()` 로 만든다. 이 함수는 보안 컨텍스트(HTTPS · `localhost`)에서만 있어서, 휴대폰으로 `http://192.168.x.x:5173` 개발 서버를 열면 없다 — 그러면 모든 그림이 오류 표시로 바뀐다. 운영(Vercel HTTPS)은 괜찮지만 모바일 우선 화면을 휴대폰에서 확인하는 길이 막힌다.
2. 학습 완료 뒤 `useLessonCompletion` 은 코스 캐시만 낡은 것으로 표시한다. 로드맵의 마지막 코스를 마치면 로드맵도 다 마침이 되는데(ADR-176), 로드맵 화면 · 로드맵 탭의 캐시는 그대로라 「다 마침」이 늦게 보일 수 있다.

## 작업

### 1. 그림 id — `MaterialBody.tsx`

- 보안 컨텍스트가 필요 없는 방법으로 바꾼다 — 예: 모듈 안 카운터 + 접두사(`material-diagram-1` …). 같은 화면의 그림 여럿 · 같은 본문을 다시 그릴 때도 id 가 겹치지 않아야 한다.
- 시험 — `crypto.randomUUID` 가 없는 환경(시험에서 지워 둔다)에서도 그림이 그려지고(Mermaid 대역의 `render` 가 불린다), 그림 둘의 id 가 서로 다르다.

### 2. 학습 완료 뒤 로드맵 캐시 — `useLesson.ts`

- 학습 완료가 성공하면 코스 캐시와 같은 방식으로 로드맵 캐시(`roadmapsKey` — 목록과 각 로드맵)도 낡은 것으로 표시한다. 지금 코스 캐시를 다루는 방식(진행 중인 읽기 취소 · `refetchType` 등)을 그대로 따른다.
- 응답이 「코스가 다 마침이 됐는지」와 상관없이 늘 표시해도 된다 — 다시 읽기는 그 화면을 열 때만 일어난다.
- 시험 — 학습 완료 뒤 로드맵 캐시가 낡은 것으로 표시된다.

### 3. 문서

- `docs/UI_GUIDE.md` §12 · §13 에 그림 id 를 만드는 방법 · 학습 완료 뒤 비우는 캐시를 적은 문장이 있으면 맞춘다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `crypto.randomUUID` 를 더 쓰지 않는가? Mermaid 설정(`securityLevel: 'strict'` · `startOnLoad: false`)이 그대로인가?
   - 학습 완료 뒤 코스 · 로드맵 캐시가 모두 낡은 것으로 표시되는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/8-material-cards/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: id 를 만드는 방법 · 더한 캐시 처리 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 렌더러의 보안 설정(원본 HTML 없음 · Mermaid `strict` · KaTeX `trust` 끔)을 바꾸지 마라. 이유: 본문은 LLM 이 만든 믿지 않는 입력이다.
- 서버 코드 · API 를 고치지 마라. 이유: 이 step 은 화면만 고친다.
- 강의 화면 · 축하 화면의 구성 · 문구를 바꾸지 마라. 이유: 06 §9 · §10 대로 이미 승인됐다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
