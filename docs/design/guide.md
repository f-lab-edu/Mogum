# 머금(Mogum) UI 디자인 가이드 & 컴포넌트 명세서

이 문서는 **머금(Mogum)** 웹/모바일 서비스의 일관된 브랜드 아이덴티티, 시각 언어(Design Tokens), 공통 내비게이션 아이콘, 그리고 반응형 화면 설계 원칙을 정리한 공식 디자인 가이드라인입니다.

---

## 1. 브랜드 아이덴티티 & 핵심 에셋

### 1.1 브랜드 페르소나
- **키워드**: 따뜻함, 친근함, 부담 없는 지식 습득, 촉각적인(Tactile), 놀이 같은 학습(Playful)
- **핵심 메시지**: 한 모금씩 마시듯 가볍고 확실하게 머금는 지식

### 1.2 시그니처 마스코트 에셋
- **에셋 식별자**: `{{DATA:IMAGE:IMAGE_6}}` (`보랏빛 머그컵 속 책 읽는 찹쌀 캐릭터.png`)
- **형태**: 투명 배경 3D 렌더링 (보라색 머그컵 안에서 책을 읽는 귀여운 찹쌀떡 캐릭터)
- **적용 규칙**:
  - **헤더/로고**: 사이드바 상단 및 모바일 탑바 좌측 브랜드 심볼로 사용 (권장 크기: 32px ~ 40px)
  - **금지 사항**:
    - 우측 상단 프로필 영역에 중복 배치 금지 (사용자 요청에 따라 프로필 아바타는 간결하게 유지)
    - 어두운 배경/검은 모서리가 있는 구 버전 에셋 사용 금지

---

## 2. 디자인 토큰 (Design Tokens)

### 2.1 컬러 팔레트 (Color Palette)
- **Primary**: `#7c4dff` (Mogum Vibrant Violet) — 주요 액션, 활성 탭, 프로그레스 바
- **Primary Dark**: `#6200ea` / `#5300b8` — 버튼 호버, 강조 영역, 그라데이션 포인트
- **Primary Light / Container**: `#f3e8ff` / `#ede7f6` — 뱃지 배경, 활성 아이콘 컨테이너
- **Surface / Background**:
  - Main Background: `#fff7fe` (은은한 연보라빛 틴트 배경)
  - Surface Container Lowest: `#ffffff` (카드 및 모달 기본 흰색 배경)
  - Surface Container Low: `#fbf0ff`
  - Surface Dim / Border: `#f1e5f5` / `#e9d5ff`
- **Text & Neutral**:
  - Text Primary: `#1e1b2e` (깊은 다크 바이올렛 차콜)
  - Text Secondary: `#6b7280` / `#756f86` (중립 서브 텍스트)
  - Text Muted: `#9ca3af`
- **Functional & Accent**:
  - Streak Accent (불꽃): `#ff6d00` / `#ff9100` (따뜻한 오렌지/앰버)
  - Success / Green: `#10b981` (진행 완료 상태)

### 2.2 타이포그래피 (Typography)
- **영문/숫자**: `Plus Jakarta Sans`, sans-serif
- **한글**: `Pretendard`, -apple-system, BlinkMacSystemFont, system-ui, sans-serif
- **스케일**:
  - Page Title: `24px` ~ `28px` (Bold / Extrabold, tracking-tight)
  - Section Title: `18px` ~ `20px` (Bold)
  - Body: `14px` ~ `15px` (Medium / Regular)
  - Caption / Badge: `11px` ~ `12px` (Semi-bold)

### 2.3 모서리 곡률 & 그림자 (Roundness & Shadows)
- **Radius**:
  - 대형 카드 / 배너: `rounded-3xl` (`24px`)
  - 일반 카드 / 버튼 / 뱃지: `rounded-2xl` (`16px`)
  - 인라인 태그 / 아이콘 컨테이너: `rounded-xl` (`12px`)
- **Shadow**:
  - `shadow-sm` / `shadow-md` (색조가 가미된 은은한 퍼플 섀도우: `rgba(124, 77, 255, 0.06)`)
  - 과도하게 어둡거나 둔탁한 그림자 지양

---

## 3. 표준 내비게이션 5대 탭 아이콘 (Navigation Icons)

모든 화면의 **모바일 하단 탭 바** 및 **데스크톱 사이드바**에는 기획 문서(`05-screens.md`)에서 확정한 5대 탭과 전용 아이콘을 동일하게 유지합니다.

| 탭 이름 | 역할 및 의미 | 아이콘 콘셉트 & SVG 형태 |
| :--- | :--- | :--- |
| **1. 홈 (Home)** | 오늘 할 일 모음, 스트릭, 이어서 배우기, 새로 배우기 입구 | **둥근 홈 심볼**<br>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 10.5L12 3l9 7.5v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9z"/><polyline points="9 21 9 12 15 12 15 21"/></svg>` |
| **2. 코스 (Courses)** | 모든 개별 코스 목록 열람 및 학습 화면 진입로 | **학사모 심볼**<br>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 10v6M2 10l10-5 10 5-10 5z"/><path d="M6 12v5c3 3 9 3 12 0v-5"/></svg>` |
| **3. 로드맵 (Roadmap)** | 단계별 코스 계획 및 '다음 코스 만들기' 진행 뷰 | **경로 노드 / 루트(Route) 심볼**<br>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a4.5 4.5 0 0 0 4.5-4.5v-5A4.5 4.5 0 0 0 17.5 5H9"/><circle cx="6" cy="5" r="3"/></svg>` |
| **4. 복습 (Review)** | FSRS 기반 당일 복습 플래시카드 덱 (복습 탭 = 복습 화면) | **플래시카드 덱 심볼**<br>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="14" height="15" rx="2"/><path d="M7 2.5h12a2 2 0 0 1 2 2v13"/></svg>` |
| **5. 내 정보 (Profile)** | 계정 정보, 로그아웃, 탈퇴, 개인정보처리방침 | **단정하고 둥근 사용자 프로필 심볼**<br>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>` |

---

## 4. 반응형 레이아웃 원칙 (Adaptive Layout Principles)

머금의 웹 환경은 **"브라우저에서 여는 웹이지만 앱처럼 보이게 만든다(ADR-068)"**는 기획 철학을 따릅니다.

### 4.1 모바일 뷰포트 (`< 1024px`)
- **구조**:
  - **상단 헤더**: 좌측 머그컵 마스코트 + 서비스명, 우측 스트릭 불꽃 카운터만 간결하게 배치
  - **본문**: 단일 컬럼(`flex flex-col gap-4`) 세로 스크롤 방식
  - **하단 고정 바**: 5대 탭 바(`fixed bottom-0 z-50`, `bg-white/95 backdrop-blur-md`)
  - **패딩**: 하단 탭 바 가림 방지를 위해 `pb-24` 확보

### 4.2 데스크톱 와이드 뷰포트 (`≥ 1024px`)
- **구조**:
  - **좌측 사이드바**: 고정 너비(`w-64`), 머그컵 브랜드 로고 + 5대 메뉴 + 오늘의 목표 진도 요약 위젯
  - **상단 영역**: 검색창이나 불필요한 티어 정보 없이, 상단 우측에 깔끔한 **스트릭 위젯** 유지
  - **본문 대시보드 그리드**: 12컬럼 시스템(`lg:grid-cols-12`)
    - **메인 영역 (8 Cols)**: 일일 핵심 과업 (환영 배너, 복습 카드 섹션, 최근 학습 코스, 새로 배우기 배너)
    - **우측 사이드 패널 (4 Cols)**: 할 일이 생긴 것(검토 대기 코스 등), AI 생성 중 현황, 머금 학습 꿀팁 팁 카드

### 4.3 불필요한 요소 배제 원칙 (기획 명세 엄수)
- **스트릭**: 연속 일수와 오늘 채웠는지만 표기 (복잡한 잔디 달력/과거 기록 지양)
- **복습 카드**: 에빙하우스 알고리즘 설명, 소요 시간, 복잡한 통계 수치 배제 → "남은 카드 수"와 "즉시 시작 액션"에 집중
- **시스템 정보 은닉**: 토큰 수, 비용, LLM 모델명, 재시도 횟수 등 내부 시스템 정보는 사용자 화면에 일절 노출하지 않음

---

## 5. 향후 화면 확장 가이드

다음 화면들을 제작할 때 위 규격을 기본 템플릿으로 상속하여 일관성을 유지합니다:
1. **코스 화면 (`Course Detail`)**: '검토 중(이대로 시작)' ↔ '학습 중(진도율 및 강의 열기)' ↔ '다 마침' 3가지 상태 명확화
2. **복습 탭 / 화면 (`Flashcard Review`)**: 탭 진입 시 즉시 카드 1장 노출, 질문 → 뒤집기/답 보기 → 4단계 자체 평가 (다시/어려움/좋음/쉬움)
3. **로드맵 화면 (`Roadmap View`)**: 계획된 코스 순서 노드, '다음 코스 만들기' 액션 연결
4. **새로 배우기 화면 (`Course Creator`)**: 주제, 현재 수준, 목적, 로드맵 체크박스 간결 폼
