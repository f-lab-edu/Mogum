# Step 0: interval-unit-carry

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/UI_GUIDE.md` §15(복습 탭) — `formatReviewInterval` 을 설명하는 문단. 이 step 전에 규칙을 고쳐 커밋했다
- `docs/06-screens.md` §15(복습 탭 — 평가 버튼 아래 다음 간격)
- `frontend/src/lib/formatReviewInterval.ts` · `frontend/src/lib/formatReviewInterval.test.ts`
- `frontend/src/pages/ReviewPage.tsx` · `ReviewPage.test.tsx` — 이 함수를 쓰는 곳(간격 문구를 기대하는 시험이 있는지 본다)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

복습 탭의 평가 버튼은 등급 이름 아래 다음 간격을 「10분」 · 「1일」처럼 보인다. 지금 `formatReviewInterval` 은 단위를 **반올림 전 값**으로 고른 뒤 반올림해서, 3,599초가 「60분」, 86,399초가 「24시간」, 30일 바로 전이 「30일」, 365일 바로 전이 「12달」로 나온다. `docs/UI_GUIDE.md` §15 의 규칙을 「반올림한 수가 다음 단위의 크기에 닿으면 다음 단위의 1로 쓴다」로 고쳤다. 코드와 시험을 그 규칙에 맞춘다.

## 작업

- 시험부터 — `frontend/src/lib/formatReviewInterval.test.ts` 의 기대값을 `docs/UI_GUIDE.md` §15 에 맞춘다.
    - 바뀌는 값: `3599` → 「1시간」 · `86399` → 「1일」 · `30 * 86400 - 1` → 「1달」 · `365 * 86400 - 1` → 「1년」
    - 올림 경계를 더한다: 반올림이 다음 단위에 닿는 가장 작은 값과 그 바로 앞 값(예: `3570` → 「1시간」, `3569` → 「59분」 · `84600` → 「1일」, `84599` → 「23시간」 · 일 → 달 · 달 → 년도 같은 방식으로 계산해 넣는다). 경계 값은 직접 계산해 적고, 이유를 테스트 이름이나 주석에 남기지 않아도 된다
    - 나머지 기존 행(`0` → 「1분」 · `90` → 「2분」 · `3600` → 「1시간」 · `30 * 86400` → 「1달」 · `365 * 86400` → 「1년」 · `547.5 * 86400` → 「2년」 등)은 그대로 통과해야 한다
- 구현 — `frontend/src/lib/formatReviewInterval.ts` 의 `formatReviewInterval(seconds: number): string` 시그니처를 유지하고 위 규칙을 만족하게 고친다. 단위와 크기(분 60초 · 시간 3,600초 · 일 86,400초 · 달 30일 · 년 365일)는 바꾸지 않는다. 년은 가장 큰 단위라 올리지 않는다.
- `ReviewPage.test.tsx` 등 이 함수의 문구를 기대하는 다른 시험이 있으면 새 규칙에 맞는지 확인한다(대개 바뀌지 않는다).
- 문서 — `docs/UI_GUIDE.md` §15 는 이미 고쳤다. 구현이 그 문장과 다르게 되면 코드를 문장에 맞춘다.

## Acceptance Criteria

```bash
npm --prefix frontend run test
npm --prefix frontend run lint
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - `docs/UI_GUIDE.md` §15 의 예(0초 「1분」 · 90초 「2분」 · 3,599초 「1시간」 · 86,399초 「1일」 · 30일 바로 전 「1달」 · 365일 바로 전 「1년」)가 모두 시험에 있고 통과하는가?
   - 「60분」 · 「24시간」 · 「30일」 · 「12달」이 나오는 입력이 남지 않았는가?
   - `AGENTS.md` 금지 목록 · 작업 방식(TDD)을 위반하지 않았는가?
3. 결과에 따라 `phases/9a-review-interval/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: 바뀐 기대값 · 더한 경계 행 · 화면 테스트 수(전후)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (계약 문서와 코드가 맞지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 서버가 주는 초(`nextIntervalsSeconds`)나 FSRS 일정을 바꾸지 마라. 이유: 문구만 바꾸는 step 이다 — 간격은 라이브러리 계산 그대로다(`AGENTS.md` 금지 21).
- 단위 이름(분 · 시간 · 일 · 달 · 년)이나 단위 크기(달 30일 · 년 365일)를 바꾸지 마라. 「주」 같은 새 단위를 더하지 마라. 이유: UI_GUIDE §15 가 정했다.
- 복습 탭(`ReviewPage`)의 동작 · 배치 · 「카드 지우기」 입구 자리를 고치지 마라. 이유: 이 step 범위가 아니다 — 실사용(M1) 뒤 정한다.
- 백엔드(`backend/`)를 고치지 마라.
- 계약 문서(`docs/UI_GUIDE.md` §15 의 규칙 문장 · ADR · `AGENTS.md`)를 고치지 마라. 이유: step 전에 정해 커밋했다. 어긋나면 `blocked`.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
