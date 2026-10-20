이 프로젝트의 변경 사항을 리뷰하라. 인자로 task 이름이 오면(`$ARGUMENTS`) `phases/$ARGUMENTS/` 의 phase 를, 없으면 현재 브랜치를 리뷰한다.

구현은 Codex 가 했다. 너는 리뷰만 한다 — 제품 코드(`backend/` · `frontend/`)를 직접 고치지 않는다.

먼저 다음 문서들을 읽어라:
- `AGENTS.md` (금지 목록 · 작업 방식)
- `docs/ARCHITECTURE.md`
- `docs/ADR.md`
- phase 리뷰라면 `phases/{task}/index.json` 과 각 `step{N}.md` · `step{N}-review.json`(step 단위 리뷰 기록) · `step{N}-output.json`(Codex 실행 기록)
- `phases/HARNESS-BACKLOG.md` (지금까지 나온 하네스 문제 · step 작성 교훈)

그런 다음 변경을 확인한다: `git diff main...HEAD` (커밋되지 않은 변경이 있으면 `git status` 로 함께 본다). `phases/` 메타데이터는 제외한다.

## 체크리스트

1. **아키텍처 준수**: 도메인별 패키지 구조와 그 안의 `controller` · `service` · `repository` · `domain` · `dto` 하위 패키지(ADR-067), `generation` · `worker` 참조 제한 · OpenAI SDK 위치 · `generation` · `worker` 의 `@Transactional` 금지 · LLM 호출을 사이에 둔 엔티티 전달 금지(금지 목록 1~4)를 지키는가?
2. **기술 스택 준수**: `docs/ADR.md` 기술 선택과 `docs/01-overview.md §6` 제외 항목(금지 목록 5)을 벗어나지 않았는가?
3. **테스트 존재**: 새 기능에 테스트가 먼저 작성되어 있는가(TDD)? 실제 LLM 호출 테스트가 기본 실행에 섞이지 않았는가?
4. **금지 목록**: `AGENTS.md` 금지 목록 1~28 중 이 변경이 건드리는 항목을 위반하지 않았는가? 해당 항목 번호를 적는다.
5. **문서 일치 · 최신화**: 설계와 다르게 간 곳에서 `docs/` 를 먼저 고쳤는가? step 사이에 어긋난 곳은 없는가? 이 변경으로 바뀐 사실이 문서에 반영됐는가 — 버전 · 경로 · 명령어(`AGENTS.md`) · 디렉터리 구조(`docs/ARCHITECTURE.md`) · 🅿️ 값(`docs/PRD.md`) · "구현 · 검증은 아직" 표기(ADR · 01~07) · 진행(SCHEDULE §6 · `docs/01-overview.md` §10)? 화면이 바뀌었으면 — step 에 적힌 시안과 영역 · 문구 · 가장 강조가 맞는가(`docs/design/README.md` 「어디까지 맞추나」), 시안과 일부러 다르게 간 곳이 그 README 「문서와 다른 곳」 에 있는가?
6. **빌드 가능**: `./backend/gradlew -p backend build` · `npm --prefix frontend run lint` · `npm --prefix frontend run test` 가 통과하는가? (해당하는 것만)

## 출력 형식

| 항목 | 결과 | 비고 |
|------|------|------|
| 아키텍처 준수 | ✅/❌ | {상세} |
| 기술 스택 준수 | ✅/❌ | {상세} |
| 테스트 존재 | ✅/❌ | {상세} |
| 금지 목록 | ✅/❌ | {위반 항목 번호 · 상세} |
| 문서 일치 · 최신화 | ✅/❌ | {상세} |
| 빌드 가능 | ✅/❌ | {상세} |

위반 사항이 있으면 `파일:줄 — 무엇이 왜 틀렸나 → 고칠 방향` 으로 적는다. 사용자가 승인하면 같은 phase 에 수정 step(`step{N}.md` + `index.json` 항목 `pending`)을 추가해 Codex 에 넘긴다. step 파일은 `/harness` 의 D-3 형식을 따른다. 문서만 어긋난 것(제품 코드 변경 없음)은 수정 step 없이 Claude 가 직접 고친다.

## 하네스 회고 (phase 리뷰일 때)

표 다음에 한 단락으로 적는다. 근거는 `index.json` 의 `started_at` · `completed_at`, `step{N}-review.json` 의 판정 · 소요 시간 · 비용, `step{N}-output.json` 의 Codex 사용량 · 실행한 명령이다.

- step 별 시도 횟수 · 반려 · blocked 와 그 원인, 사람이 끼어든 지점, 시간 · 비용
- 그중 **하네스(`scripts/` · `.claude/` · `.codex/`)나 step 설계로 막을 수 있었던 것** — `phases/HARNESS-BACKLOG.md` 에 근거와 함께 바로 추가한다. 이미 있는 항목이면 관찰을 덧붙인다.
- 반복되는 step 작성 교훈은 `/harness` C 의 설계 원칙으로 올리자고 제안한다.
- 하네스를 고치는 일은 사용자 확인 뒤 `main` 에서 딴 별도 브랜치에서 한다 — phase 브랜치에 섞지 않는다.
