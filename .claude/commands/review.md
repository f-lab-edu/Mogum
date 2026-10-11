이 프로젝트의 변경 사항을 리뷰하라. 인자로 task 이름이 오면(`$ARGUMENTS`) `phases/$ARGUMENTS/` 의 phase 를, 없으면 현재 브랜치를 리뷰한다.

구현은 Codex 가 했다. 너는 리뷰만 한다 — 제품 코드(`src/` · `frontend/`)를 직접 고치지 않는다.

먼저 다음 문서들을 읽어라:
- `AGENTS.md` (금지 목록 · 작업 방식)
- `docs/ARCHITECTURE.md`
- `docs/ADR.md`
- phase 리뷰라면 `phases/{task}/index.json` 과 각 `step{N}.md` · `step{N}-review.json`(step 단위 리뷰 기록)

그런 다음 변경을 확인한다: `git diff main...HEAD` (커밋되지 않은 변경이 있으면 `git status` 로 함께 본다). `phases/` 메타데이터는 제외한다.

## 체크리스트

1. **아키텍처 준수**: `core` ← `api` · `worker` · `infra` 경계, `core.generation` 참조 제한(금지 목록 1 · 2)을 지키는가?
2. **기술 스택 준수**: `docs/ADR.md` 기술 선택과 `docs/01-overview.md §6` 제외 항목(금지 목록 5)을 벗어나지 않았는가?
3. **테스트 존재**: 새 기능에 테스트가 먼저 작성되어 있는가(TDD)? 실제 LLM 호출 테스트가 기본 실행에 섞이지 않았는가?
4. **금지 목록**: `AGENTS.md` 금지 목록 1~27 중 이 변경이 건드리는 항목을 위반하지 않았는가? 해당 항목 번호를 적는다.
5. **문서 일치**: 설계와 다르게 간 곳에서 `docs/` 를 먼저 고쳤는가? step 사이에 어긋난 곳은 없는가?
6. **빌드 가능**: `./gradlew build` · `npm --prefix frontend run lint` · `npm --prefix frontend run test` 가 통과하는가? (해당하는 것만)

## 출력 형식

| 항목 | 결과 | 비고 |
|------|------|------|
| 아키텍처 준수 | ✅/❌ | {상세} |
| 기술 스택 준수 | ✅/❌ | {상세} |
| 테스트 존재 | ✅/❌ | {상세} |
| 금지 목록 | ✅/❌ | {위반 항목 번호 · 상세} |
| 문서 일치 | ✅/❌ | {상세} |
| 빌드 가능 | ✅/❌ | {상세} |

위반 사항이 있으면 `파일:줄 — 무엇이 왜 틀렸나 → 고칠 방향` 으로 적는다. 사용자가 승인하면 같은 phase 에 수정 step(`step{N}.md` + `index.json` 항목 `pending`)을 추가해 Codex 에 넘긴다. step 파일은 `/harness` 의 D-3 형식을 따른다.
