# 화면 시안 — Claude Design 사본

- 출처 — Claude Design 프로젝트 「머금 강의 화면 설계」(https://claude.ai/design/p/c01ce1b4-dfd2-49d4-8ba2-eb90d90c92de). 2026-10-12 에 가져왔다.
- **참고 자료다.** 화면 구조 · 상태 · 문구는 [06 화면](../06-screens.md), 색 · 글꼴 · 컴포넌트는 [UI_GUIDE](../UI_GUIDE.md) 가 정한다. 시안과 문서가 다르면 문서가 맞다.
- **완성형이다.** 시안은 공개 시점의 모습을 다 그렸다. 언제 무엇을 만드는지는 [06 §16](../06-screens.md) 이 정한다 — step 에 적힌 상태만 만든다.
- 여기서 직접 고치지 않는다. 시안을 고치면 Claude Design 에서 다시 가져와 폴더를 통째로 바꾼다. 시안 런타임(`support.js`)은 가져오지 않는다.

## 무엇을 볼 때 어느 파일을 여나

| 만드는 것 | 연다 |
|---|---|
| 화면 하나 | 아래 「화면별 시안」 의 그 화면 파일 하나 — 필요한 상태 값만 |
| 여러 화면이 같이 쓰는 조각 | 아래 「공통 조각」 — 대표 파일 · 상태 하나 |
| 화면 사이 이동(라우팅 · 버튼이 가는 곳) | `화면 흐름 보드.dc.html` — 그 흐름(S1~S7)만 |

`머금 화면.dc.html`(프레임 모음)은 구현할 때 열지 않는다. 화면 파일을 상태별로 늘어놓은 것이라 내용이 같다.

## 화면별 시안

상태 값은 파일의 `state`(Entry · Settings 는 `screen`) 속성이다. 프레임 이름은 06 §17.4 와 `머금 화면.dc.html` 의 이름이다. M = 모바일 390 · D = 데스크톱 1280.

| 화면 | 06 | 파일 | 상태 값 → 프레임 |
|---|---|---|---|
| U1 로그인 | §3 | `Entry Screens.dc.html` | `u1` → M① 기본 · D③ · `u1relogin` → M② 다시 로그인해 주세요 |
| U7 가입 | §4 | `Entry Screens.dc.html` | `u7terms` → M① 약관 동의(`checked` 를 켜면 D④ 체크한 상태) · `u7intro1` → M② 소개 1장 · `u7intro3` → M③ 소개 3장 |
| U8 대기 명단 | §5 | `Entry Screens.dc.html` | `u8` → M① 대기 3번째 · D② |
| U2 홈 | §6 | `U2 Screen.dc.html` | `first` → M① 처음 온 사용자 · `normal` → M② 평소 · D⑤ · `requests` → M③ 짜는 중 · 짜기 실패 요청 · `doneBlocked` → M④ 오늘 복습 끝 + 막힘(막힘 문구는 `scrollTo`=`form`) |
| U10 커리큘럼 만드는 중 | §7 | `U10 Screen.dc.html` | `queued` → M① 짜는 중 · `failRetry` → M② 실패 — 다시 시도 가능 · `failRejected` → M③ 실패 — 주제 거절 |
| U3 커리큘럼 | §8 | `U3 Screen.dc.html` | `review` → M① 검토 대기 · D⑨ · `regenOpen` → M② 다시 짜기 펼침 · `regenerating` → M③ 다시 짜는 중 · `regenFailed` → M④ 다시 짜기 실패 · `active` → M⑤ 학습 중 · `deleteOpen` → M⑥ 삭제 확인 펼침 · `doneSingle` → M⑦ 완료 — 하나짜리 · `doneStep` → M⑧ 완료 — 묶음 1단계 · `firstGen` → M⑩ 단계 첫 짜기 |
| U11 커리큘럼 묶음 | §9 | `U11 Screen.dc.html` | `plan` → M① 단계 검토 · `learning` → M② 1단계 학습 중 · `nextTurn` → M③ 2단계 만들 차례 · D⑥ · `allDone` → M④ 모두 완료 · `deleteOpen` → M⑤ 묶음 삭제 확인 펼침 |
| U4 강의 | §10 | `U4 Screen.dc.html` | `queued` → M① 줄서기 · `running` → M② 만드는 중 · `slow` → M③ 평소보다 오래 걸림 · `failRetry` → M④ 실패 — 이어서 다시 시도 · `failRestart` → M⑤ 실패 — 처음부터 다시 만들기 · `blocked` → M⑥ 막힘 · `body` → M⑦ 본문 · D⑨ · `done` → M⑧ 학습 완료 후 |
| U5 복습 | §11 | `U5 Screen.dc.html` | `todayFront` → M① 오늘의 복습 앞면 · `todayAnswer` → M② 답 + 평가(`desktop` 을 켜면 D⑦) · `firstFront` → M③ 첫 복습 앞면 · `saveFail` → M④ 저장 실패 · `endFirst` → M⑤ 끝 — 첫 복습 · `endToday` → M⑥ 끝 — 오늘의 복습 · `waitReady` → M⑧ 기다리는 동안 준비 알림 · `endStopped` → M⑨ 끝 — 그만했을 때 |
| U9 설정 | §12 | `Settings Screens.dc.html` | `u9` → M① 기본 · `u9withdraw` → M② 탈퇴 펼침 |
| P1 · P2 약관 | §13 | `Settings Screens.dc.html` | `p1` → M① 이용약관(P2 는 같은 틀) |
| U6 시스템 상태 | §14 | `U6 Screen.dc.html` | `empty` 끔 → D① 평소 · 켬 → D② 데이터 없음 |

## 공통 조각

| 조각 | 06 · UI_GUIDE | 대표 시안 |
|---|---|---|
| 상단 바 | §2.5 | 어느 화면 파일이든 `data-layer="상단 바"`. 현재 화면 표시는 U2(홈) · U5(복습) · Settings `u9`(설정) · U6(시스템 상태 — 관리자) |
| 대기 블록 — 강의형(단계 목록) | §2.8 | U4 `queued` · `running` · `slow` |
| 대기 블록 — 커리큘럼형(한 줄) · 회색 줄 | §2.8 | U10 `queued` · U3 `regenerating`(이전 안 흐리게) · `firstGen` |
| 실패 문구 + 다시 시도 | §15.2 | U4 `failRetry` · `failRestart` · U10 `failRetry` · `failRejected` · U3 `regenFailed` |
| 막힘 문구 + 지금 할 수 있는 것 | §15.1 | U4 `blocked`(링크 둘) · U2 `doneBlocked`(복습 0장 — 링크 없음) |
| 그 자리 확인 펼침 | §2.7 | U3 `deleteOpen` · U11 `deleteOpen` · Settings `u9withdraw`(체크해야 눌림) |
| 화면 아래 고정 행동 자리 | UI_GUIDE 레이아웃 | U3 결정 바(`review` · `regenerating` · `regenFailed` · `firstGen`) · U5 답 보기 · 평가(`todayFront` · `todayAnswer`) |
| 막대(진도 · 생성량) | UI_GUIDE 막대 | 진도 — U2 `normal` · U3 `active` · U11 `learning` · U5 머리. 오늘 생성량 — U2 `first` · Settings `u9` |
| 강의 본문(제목 · 목록 · mermaid · KaTeX · 표 · 그림 실패) | UI_GUIDE 강의 본문 | U4 `body` |
| 체크박스 | UI_GUIDE 체크박스 | Entry `u7terms` · Settings `u9withdraw` |

## 흐름 — 화면 사이 이동

`화면 흐름 보드.dc.html` 의 `S1`~`S7` 상수(파일 아래 스크립트)가 흐름 하나씩이다. 각 흐름의 `lanes` 가 정상 경로, `branches` 가 실패 · 막힘 · 빠지는 길, 화살표 글자가 누르는 버튼이다.

| 흐름 | 내용 |
|---|---|
| S1 처음 온 사람 | U1 → U7 → U2 → U10 → U3 → U4 → U5 → 다음 강의 · 정원 초과 → U8 |
| S2 매일 복습 | U2 → U5 → U2 · 그만하기 · 저장 실패 |
| S3 강의 하나 학습 | U2 이어서 → U4 → 기다리는 동안 U5 → U4 → 첫 복습 → 다음 강의 |
| S4 새 주제 시작 | U2 폼 → U10 → U3 다시 짜기 → U4 · 주제 거절 |
| S5 넓은 주제 | U10 → U11 → 단계 U3 → … → U11 모두 완료 |
| S6 막혔을 때 | 시작점 → §15.1 문구 → 지금 할 수 있는 것 |
| S7 정리 | 커리큘럼 · 묶음 삭제 · 실패 요청 지우기 · 탈퇴 |

보드 옆 「흐름이 끊기는 곳」 메모는 시안을 그릴 때의 기록이다. 「해결」 로 적힌 것은 06 에 반영됐다 — 06 을 따른다.

## 읽는 법

띄우지 않아도 된다 — 마크업으로 읽는다. 이 폴더에는 시안 런타임이 없어 브라우저로 열면 그려지지 않는다. 그림으로 보려면 위 Claude Design 링크에서 연다.

1. `grep -n 'data-layer' '<파일>'` 로 영역을 훑는다. 영역 이름에 06 의 영역 번호가 붙어 있다(`U2-1 오늘의 복습` …).
2. 파일 아래 `<script type="text/x-dc">` 의 `renderVals()` 에서 상태 값이 어떤 플래그를 켜는지 본다.
3. 그 플래그의 `<sc-if value="{{ … }}">` 블록이 그 상태에서 보이는 것이다. `<sc-for>` 는 반복, `{{ … }}` 는 데이터 자리다.

`scrollTo` 속성은 프레임을 찍으려고 스크롤 위치만 정한 것이다 — 상태가 아니다.

## 값 옮기기 — 인라인 스타일 → UI_GUIDE 토큰

인라인 값은 그대로 옮기지 않는다. 가까운 토큰 · Tailwind 값으로 쓴다.

| 시안 | 토큰 | 시안 | 토큰 |
|---|---|---|---|
| `#FAF8F3` | `paper` | `#2F4B7C` | `accent` |
| `#FFFFFF` | `surface` | `#253D66` | `accent-strong` |
| `#F1EEE7` | `muted` | `#E8EDF5` | `accent-subtle` |
| `#E4DFD5` | `line` | `#3D7A4F` | `ok` |
| `#1F1D1A` | `ink` | `#94601A` | `warn` |
| `#3A3632` | `ink-body` | `#B4443A` | `danger` |
| `#6B655C` | `ink-soft` | `#A39D93` | `ink-faint` |

- 크기 — 높이 44 · 48 · 56px → `h-11` · `h-12` · `h-14` · 모서리 6 · 4 · 2px → `rounded-md` · `rounded` · `rounded-sm` · 카드 안 여백 20px → `p-5` · 영역 사이 32px → `gap-8`(`space-y-8`)
- 글자 — 12 · 14 · 16 · 18 · 20 · 24 · 30px → `text-xs` · `text-sm` · `text-base` · `text-lg` · `text-xl` · `text-2xl` · `text-3xl`. 강의 본문 17px / 1.75 는 UI_GUIDE 강의 본문 그대로
- 폭 — `max-width: calc(48rem + 32px)` + 좌우 16px → 콘텐츠 `max-w-3xl` + `px-4` · `65ch` → `max-w-prose` · `80rem` → `max-w-7xl`

## 어디까지 맞추나

구현 · step 리뷰 · `/review` 가 같은 기준을 쓴다.

**맞춘다** — 영역의 순서와 배치 · 문구 · 상태마다 보이고 사라지는 것 · 가장 강조(Primary 하나) · 누르는 자리 크기 · 색 · 글자 크기(토큰으로).

**맞추지 않는다** — 반려 사유가 아니다.

- 예시 데이터(「경제 기초」 6강 · 23장 … — 06 §1.2). 화면은 API 값을 쓴다
- 토큰으로 옮기며 생기는 1~2px 차이
- 시안을 보여 주려는 스크립트(`renderVals` · `componentDidMount` 의 스크롤 · mermaid 초기화 순서). mermaid · KaTeX 설정은 UI_GUIDE 를 따른다
- step 에 적히지 않은 상태 — **만들지 않는다**(06 §16)

## 문서와 다른 곳

구현은 문서를 따른다.

| 화면 | 시안 | 문서 |
|---|---|---|
| U4 실패 — 처음부터 다시 만들기 | 「앞서 만든 부분은 버리고 새로 만들어요.」 가 두 번 나온다 | 한 번 — 버튼 아래 작은 글씨(06 §10 · §15.2) |

## 아직 그리지 않은 상태

흐름 보드가 스스로 적어 둔 것이다. 06 의 상태 표 · 문구대로 구현한다.

- U3 — 다시 짜기 하루 3번을 다 쓴 결정 바
- U11 — 단계 아래 막힘 문구
- U3 — 마지막 단계(3단계) 완료 · 「묶음 보기」
- U2 · U11 — 삭제 직후 · U1 — 탈퇴 직후
