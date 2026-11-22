# Step 0: spotless-format

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/CODE_STYLE.md` 전체 — **§1(네이버 컨벤션 · 도구) · §4(부딪히면 네이버가 이긴다)가 이 phase 의 계약이다**
- `docs/ADR.md` — ADR-184 · ADR-180(이름 · Lombok 은 그대로)
- `docs/README.md` 「정한 것」 2026-11-18 의 코드 스타일 두 줄
- `AGENTS.md` — 작업 방식(「백엔드 Java 를 고친 뒤 `spotlessApply`」) · 명령어
- `phases/HARNESS-BACKLOG.md` S21 — 공백만 바꾼 변경을 어떻게 검증하나
- `backend/build.gradle` · `backend/gradle/wrapper/gradle-wrapper.properties`(Gradle 9.1.0)

이전 step에서 만들어진 코드를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 배경

백엔드 Java 의 줄 배치를 **네이버 캠퍼스 핵데이 Java 코딩 컨벤션**(`naver/hackday-conventions-java`)으로 바꾼다(ADR-184). 설정 파일을 고치지 않고 그대로 쓰고, 포매터가 만든 모양이 기준이다. 이 phase 는 셋으로 나눈다 — step 0 은 **포매터(Spotless)를 두고 전체를 한 번 포맷**, step 1 은 Checkstyle 을 두고 포매터가 못 고친 위반을 고치기, step 2 는 CI · 문서다. **동작은 바꾸지 않는다.**

**계약 문서(CODE_STYLE · ADR-184 · AGENTS · README)는 이 phase 전에 정해 커밋했다. 코드가 계약과 어긋나면 코드를 계약에 맞추고, 맞출 수 없으면 `blocked` 로 멈춰라. 계약 문장을 고치지 마라.**

## 작업

### 1. 네이버 설정 파일을 가져온다 — `backend/config/naver/`

**커밋 `e12658edad4017e2c00ef560704129901770cc2b` 에 고정해** 아래 여섯 파일을 받는다. 내용을 고치지 마라(줄바꿈 · 공백 포함).

| 파일 | 원본 경로(raw) |
|---|---|
| `naver-eclipse-formatter.xml` | `https://raw.githubusercontent.com/naver/hackday-conventions-java/e12658edad4017e2c00ef560704129901770cc2b/rule-config/naver-eclipse-formatter.xml` |
| `naver.importorder` | 같은 커밋의 `rule-config/naver.importorder` |
| `naver-checkstyle-rules.xml` · `naver-checkstyle-suppressions.xml` | 같은 커밋의 `rule-config/` |
| `naver-intellij-formatter.xml` | 같은 커밋의 `rule-config/` |
| `.editorconfig` | 같은 커밋의 저장소 루트 — **우리 저장소 루트**에 둔다(`backend/config/naver/` 가 아니다) |

- 받은 파일의 크기(바이트)를 summary 에 적는다 — 2026-11-18 Claude 가 같은 커밋에서 받은 크기는 순서대로 30852 · 139 · 17238 · 182 · 2879 · 229 다. 다르면 `blocked`.
- 우리 저장소 루트에 이미 `.editorconfig` 가 있으면 덮어쓰지 말고 `blocked`.
- `backend/config/naver/README.md` 를 짧게 쓴다 — 출처 URL · 커밋 SHA · 가져온 날 · **원본 저장소에 라이선스 파일이 없다**는 사실 · 「고치지 않는다(ADR-184)」 · IntelliJ 가져오는 법.

### 2. Spotless — `backend/build.gradle`

- 플러그인 `com.diffplug.spotless` **8.12.0**(2026-11-18 Gradle 플러그인 포털의 최신). Gradle 9.1.0 · Java 21 에서 동작하지 않으면 동작하는 가장 가까운 버전을 쓰고 까닭을 summary 에 적는다.
- `java` 대상: `backend/src/main/java/**` · `backend/src/test/java/**`.
    - `eclipse().configFile('config/naver/naver-eclipse-formatter.xml')` — Eclipse 버전은 Spotless 기본.
    - `importOrderFile('config/naver/naver.importorder')`
    - `trimTrailingWhitespace()` · `endWithNewline()`
    - **그 밖의 단계(`removeUnusedImports` · `googleJavaFormat` · 라이선스 머리말 …)를 더하지 마라** — 네이버 설정에 없는 일이다.
- `spotlessCheck` 가 `check` 에 걸리는 기본 동작을 그대로 둔다.
- Lombok 주석의 `docs/CODE_STYLE.md §4` 를 `§3` 으로 고친다(문서의 절이 바뀌었다).

### 3. 한 번에 포맷한다

- `./backend/gradlew -p backend spotlessApply` 로 백엔드 Java 전체를 포맷한다. **손으로 줄을 맞추지 마라** — 포매터 결과가 기준이다(CODE_STYLE §1).
- 포매터가 텍스트 블록(`"""`) · 문자열의 **내용**을 바꾸면 안 된다 — 동작이 바뀐다. 바뀌면 `blocked`.
- 이 step 에서는 Checkstyle 을 두지 않는다(step 1).

### 4. 검증 — 동작이 바뀌지 않았다

- 파일마다 **import 줄을 뺀 나머지**에서 공백(스페이스 · 탭 · 줄바꿈)을 모두 지운 문자열이 포맷 전과 같고, **import 줄의 집합**이 포맷 전과 같은지 본다(import 는 순서만 바뀐다). `git diff -w` 의 표시로 판단하지 마라(S21). 비교는 저장소에 남기지 않는 임시 스크립트로 하고 결과(파일 수 · 모두 같음)를 summary 에 적는다.
- 텍스트 블록 · 문자열 리터럴의 내용이 같은지는 위 비교가 잡는다(공백만 다르면 따로 본다 — 텍스트 블록 안의 공백은 값이다).
- 서버 전체 테스트가 통과하고 **테스트 수가 포맷 전과 같다**.

## Acceptance Criteria

```bash
./backend/gradlew -p backend spotlessCheck
./backend/gradlew -p backend build   # Testcontainers — Docker 필요. step 1 전이라 Checkstyle 은 아직 없다
```

## 검증 절차

1. 위 AC 커맨드를 실행한다.
2. 아키텍처 체크리스트를 확인한다:
   - 네이버 설정 파일 여섯이 커밋 `e12658e` 그대로인가(크기)? `backend/config/naver/README.md` 에 출처 · 커밋 · 라이선스 없음이 있는가?
   - Spotless 단계가 네이버 포매터 · import 순서 · 줄 끝 공백 · 파일 끝 줄바꿈뿐인가?
   - import 를 뺀 공백 제거 비교 · import 집합 비교가 모두 같은가? 테스트 수가 같은가?
   - 화면 · 문서 · 하네스를 건드리지 않았는가(`.editorconfig` · `backend/config/naver/` · `backend/build.gradle` 과 포맷된 Java 만)?
3. 결과에 따라 `phases/7a-naver-style/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "산출물 한 줄 요약"`. 담을 것: Spotless 버전 · 설정 · 받은 파일 크기 · 포맷한 파일 수 · 비교 결과 · 서버 테스트 수(전후 같음)
   - 수정 3회 시도 후에도 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 (파일 크기 다름 · 문자열 내용이 바뀜 · 플러그인이 동작하지 않음 등) → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- 네이버 설정 파일의 내용을 고치지 마라. 이유: 설정 그대로 따르기로 했다(ADR-184).
- 손으로 줄 배치를 바꾸거나 포매터 결과를 되돌리지 마라. 이유: 포매터 결과가 기준이다(CODE_STYLE §1).
- 이름 · 로직 · 주석의 글자를 바꾸지 마라(Lombok 주석의 절 번호 하나만 예외). 이유: 동작을 바꾸지 않는 phase 다. 이름 바꾸기는 step 1 이다.
- Checkstyle · CI 워크플로를 이 step 에서 바꾸지 마라. 이유: step 1 · 2 의 범위다.
- 화면(`frontend/`) · 문서(`docs/`)를 고치지 마라. 이유: 계약 문서는 정해 두었고, 확정 사실은 step 2 가 적는다.
- 커밋하지 마라. 이유: Claude 리뷰가 승인한 뒤 하네스가 커밋한다.
- 기존 테스트를 깨뜨리지 마라
