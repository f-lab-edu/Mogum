# UI 디자인 가이드

> **시각 디자인의 단일 출처다.** 화면 구조 · 상태 · 문구는 [06 화면](./06-screens.md)이 정한다 — 이 문서는 *어떻게 보이나* 만 정한다.
> 화면 코드는 `frontend/` · React + Tailwind CSS. 아래 값은 `tailwind.config` 의 테마 토큰으로 둔다.

## 디자인 원칙

1. **종이 위의 강의처럼 읽힌다.** 핵심 화면(U4 강의 · U5 복습)은 긴 한국어 글을 이동 중에 읽는 곳이다 — 가독성이 장식보다 먼저다.
2. **도구처럼 차분하다.** 마케팅 페이지가 아니라 매일 쓰는 학습 도구. 강조는 한 화면에 하나(가장 중요한 버튼)만.
3. **정직하게 보인다.** 기다림은 단계 목록으로, 막힘은 이유 · 언제 풀리나 · 지금 할 수 있는 것으로. 가짜 퍼센트 막대 · 시스템 용어 없음(06 §2.1).
4. **평가를 기울이지 않는다.** 복습 평가 버튼 4개는 같은 모양이다 — 색으로 좋고 나쁨을 말하면 정직한 자가평가가 무너지고 다음 강의가 틀리게 맞춰진다.

## AI 슬롭 안티패턴 — 하지 마라

| 금지 사항 | 이유 |
|-----------|------|
| backdrop-filter: blur() | glass morphism은 AI 템플릿의 가장 흔한 징후 |
| gradient-text (배경 그라데이션 텍스트) | AI가 만든 SaaS 랜딩의 1번 특징 |
| "Powered by AI" 배지 | 기능이 아니라 장식. 사용자에게 가치 없음 |
| box-shadow 글로우 애니메이션 | 네온 글로우 = AI 슬롭 |
| 보라/인디고 브랜드 색상 | "AI = 보라색" 클리셰 |
| 모든 카드에 동일한 rounded-2xl | 균일한 둥근 모서리는 템플릿 느낌 |
| 배경 gradient orb (blur-3xl 원형) | 모든 AI 랜딩 페이지에 있는 장식 |
| 모달 · 브라우저 확인창 | 되돌릴 수 없는 동작은 **그 자리에서 다시 묻는다**(06 §2.5) |
| 기다리는 화면의 진행 막대 · 화면 가운데 스피너 | 서버는 *단계*만 안다. 첫 로딩은 회색 줄(스켈레톤) |
| 평가 버튼의 빨강~초록 색 구분 | 「기억 안 났어요」 를 누르기 꺼리게 만든다 — 원칙 4 |
| 다크 모드 | 트리거 전까지 만들지 않는다([01 §6](./01-overview.md)) |

## 색상

### 배경 · 선

| 용도 | 토큰 | 값 |
|------|------|------|
| 페이지 | `paper` | `#FAF8F3` |
| 카드 · 입력 · 상단 바 | `surface` | `#FFFFFF` |
| 코드 블록 · 회색 줄 · 그림 실패 상자 | `muted` | `#F1EEE7` |
| 선 · 구분선 · 테두리 | `line` | `#E4DFD5` |

### 텍스트

| 용도 | 토큰 | 값 |
|------|------|------|
| 주 텍스트 · 제목 | `ink` | `#1F1D1A` |
| 본문 | `ink-body` | `#3A3632` |
| 보조 · 작은 글씨 · 출처 | `ink-soft` | `#6B655C` |
| 비활성 · 자리표시 | `ink-faint` | `#A39D93` |

### 포인트 — 쪽빛 하나

| 용도 | 토큰 | 값 |
|------|------|------|
| 강조 버튼 · 링크 · 현재 위치 · 진도 막대 | `accent` | `#2F4B7C` |
| 눌림 · hover | `accent-strong` | `#253D66` |
| 옅은 바탕(현재 단계 · 선택된 항목) | `accent-subtle` | `#E8EDF5` |

### 데이터/시맨틱 색상

**상태 표시와 문구에만** 쓴다. 버튼 바탕 · 평가 버튼에는 쓰지 않는다(탈퇴 버튼만 예외).

| 용도 | 토큰 | 값 | 쓰는 곳 |
|------|------|------|------|
| 성공 · 완료 | `ok` | `#3D7A4F` | 「완료」 표시 · 단계 목록 ✔ |
| 주의 | `warn` | `#94601A` | 「평소보다 조금 더 걸리고 있어요」 · 예정일 지난 카드 |
| 오류 · 막힘 | `danger` | `#B4443A` | §12 막힘 · 실패 문구 · 탈퇴 버튼 |

> 대비 — 글자로 쓰는 `ink-body` · `ink-soft` · `accent` · `ok` · `warn` · `danger` 는 `paper` · `surface` 위에서 WCAG AA(4.5:1) 이상이어야 한다. 값을 바꾸면 다시 잰다.

## 컴포넌트

### 카드 · 영역

```
rounded-md bg-surface border border-line p-5
```
영역끼리는 그림자 대신 **선과 간격**으로 나눈다. 그림자는 쓰지 않는다.

### 버튼

```
Primary(화면당 하나): rounded-md bg-accent text-white px-4 h-11 hover:bg-accent-strong
Secondary:            rounded-md border border-line bg-surface text-ink px-4 h-11 hover:bg-muted
Text · 링크:          text-accent underline-offset-4 hover:underline
Danger(탈퇴만):       rounded-md border border-danger text-danger px-4 h-11
잠김:                 opacity-50 cursor-not-allowed — 이유를 버튼 아래 작은 글씨로(06 문구)
```

### 복습 평가 버튼 — 4개 모두 같다

```
rounded-md border border-line bg-surface text-ink h-12 min-w-[44px] hover:bg-muted
안에 작은 글씨로 키보드 숫자: <span class="text-ink-faint text-xs">1</span>
배치: 데스크톱 가로 한 줄 gap-3 · 모바일 2×2 gap-3
```

### 입력 필드

```
rounded-md bg-surface border border-line px-3 h-11 text-ink placeholder:text-ink-faint
focus:outline-none focus:ring-2 focus:ring-accent
글자 수 표시: 입력 아래 오른쪽 text-xs text-ink-soft 「⟨0⟩ / 100」
```

### 상태 표시 · 글자표

```
상태 글자(「학습 중」 · 「검토 대기」 …): text-sm text-ink-soft — 바탕색 없음
완료:  text-sm text-ok
키워드 · 개념 글자표: rounded-sm bg-muted text-ink-soft text-xs px-2 py-0.5
```

### 막대(진도 · 오늘 생성량 · 복습 장수만)

```
h-1.5 rounded-full bg-muted → 채움 bg-accent
```

### 강의 본문(마크다운)

```
본문: text-[17px] leading-[1.75] text-ink-body max-w-prose
제목(##): text-xl font-semibold text-ink mt-10 mb-3
코드 블록: rounded-md bg-muted font-mono text-sm p-4 overflow-x-auto
수식($$) · 그림(mermaid) · 표: 블록 안에서만 overflow-x-auto — 본문 전체가 옆으로 밀리지 않는다
그림 실패: rounded-md bg-muted text-ink-soft p-6 「그림을 표시하지 못했어요.」
```

- **mermaid** — `securityLevel: 'strict'` · `theme: 'base'` · `themeVariables` 를 위 토큰으로(`primaryColor: #E8EDF5` · `primaryBorderColor: #2F4B7C` · `lineColor: #6B655C` · `fontFamily: Pretendard`).
- **KaTeX** — 기본 스타일 · 글자색은 `ink-body` 를 따른다.
- 둘 다 U4 에서만 지연 로딩한다.

### 포커스

```
focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
```
키보드 사용자에게 언제나 보인다. 영역이 바뀌면 포커스를 옮긴다(06 §2.7).

## 레이아웃

- **U4 강의 · U5 복습** — 모바일(390) 우선. 본문 폭 `max-w-prose`(한 줄 약 36자) · 좌우 여백 `px-4`. 데스크톱은 같은 틀을 가운데에.
- **그 밖의 화면** — 데스크톱(1280) 기준 `max-w-3xl mx-auto` · 모바일에서는 한 단으로 쌓인다.
- 정렬: **좌측 정렬 기본.** 가운데 정렬은 U1 소개 · U5 카드 · U8 큰 숫자에만.
- 간격: 요소 사이 `gap-3`~`gap-4`, 영역 사이 `space-y-8`. U3 [U3-5] 결정 버튼은 화면 아래에 붙는다(`sticky bottom-0`).
- 상단 바: `h-14 bg-surface border-b border-line` · **텍스트 링크**(아이콘 아님) · 현재 화면 링크는 `text-accent font-semibold`.

## 타이포그래피

글꼴은 **Pretendard 하나**(가변 폰트 · `font-sans`). 코드만 **JetBrains Mono**(`font-mono`, 없으면 시스템 고정폭).

| 용도 | 스타일 |
|------|--------|
| 페이지 제목 | `text-2xl font-semibold text-ink` |
| 영역 소제목 | `text-lg font-semibold text-ink` |
| 강의 본문 | `text-[17px] leading-[1.75] text-ink-body` |
| UI 본문 · 버튼 | `text-base text-ink-body` |
| 작은 글씨 · 출처 · 안내 | `text-sm text-ink-soft` |
| 큰 숫자(「오늘의 복습 23장」 · 「대기 3번째」) | `text-3xl font-semibold text-ink tabular-nums` |

## 애니메이션

- 허용: 영역 교체 페이드 `150ms`(기다리는 영역 → 본문 · 인라인 확인 펼침) · 버튼 hover 색 전환 `100ms` · 스켈레톤 `animate-pulse`.
- `prefers-reduced-motion` 이면 전부 끈다.
- 그 외 모든 애니메이션 금지 — 카드 뒤집기 · 슬라이드 · 바운스 · 축하 효과 없음.

## 아이콘

- 기본은 **글자**다 — 상단 바 · 버튼은 텍스트로(06 §2.3).
- 꼭 필요할 때만 인라인 SVG · `strokeWidth 1.5` · `currentColor` · 16~20px. 단계 목록의 ✔ ● ○ 정도.
- 아이콘을 둥근 배경 박스로 감싸지 않는다. 장식용 일러스트 · 이모지 없음.
