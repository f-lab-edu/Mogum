---
name: Mogum Playful Tactile Design System
colors:
  surface: '#fff7fe'
  surface-dim: '#e4d5eb'
  surface-bright: '#fff7fe'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#fbf0ff'
  surface-container: '#f8e9ff'
  surface-container-high: '#f2e3fa'
  surface-container-highest: '#ecddf4'
  on-surface: '#201828'
  on-surface-variant: '#494455'
  inverse-surface: '#362d3e'
  inverse-on-surface: '#faecff'
  outline: '#7a7487'
  outline-variant: '#cac3d8'
  surface-tint: '#6833ea'
  primary: '#632ce5'
  on-primary: '#ffffff'
  primary-container: '#7c4dff'
  on-primary-container: '#fcf6ff'
  inverse-primary: '#cdbdff'
  secondary: '#6b46c1'
  on-secondary: '#ffffff'
  secondary-container: '#a480fe'
  on-secondary-container: '#39008c'
  tertiary: '#824d00'
  on-tertiary: '#ffffff'
  tertiary-container: '#a46300'
  on-tertiary-container: '#fff7f2'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e8deff'
  primary-fixed-dim: '#cdbdff'
  on-primary-fixed: '#20005f'
  on-primary-fixed-variant: '#4f00d0'
  secondary-fixed: '#e9ddff'
  secondary-fixed-dim: '#d0bcff'
  on-secondary-fixed: '#23005c'
  on-secondary-fixed-variant: '#522aa7'
  tertiary-fixed: '#ffdcbc'
  tertiary-fixed-dim: '#ffb86b'
  on-tertiary-fixed: '#2c1700'
  on-tertiary-fixed-variant: '#683d00'
  background: '#fff7fe'
  on-background: '#201828'
  surface-variant: '#ecddf4'
  lavender-tint: '#FAF8FF'
  lavender-subtle: '#F1ECFE'
  lavender-soft: '#D6C7FF'
  primary-light: '#8C6BF7'
  primary-shade: '#5835C4'
  mug-dark: '#4C2882'
  streak-amber: '#FF9F1C'
  streak-shade: '#D97B00'
  success-mint: '#2EC4B6'
  success-shade: '#1B998B'
  danger-coral: '#FF5964'
  danger-shade: '#D63031'
  surface-cream: '#FAF8FF'
  surface-card: '#FFFFFF'
  border-card: '#EAE3FC'
  text-main: '#261E35'
  text-muted: '#756D82'
typography:
  headline-xl:
    fontFamily: Plus Jakarta Sans
    fontSize: 30px
    fontWeight: '800'
    lineHeight: 38px
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '800'
    lineHeight: 32px
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '700'
    lineHeight: 28px
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '700'
    lineHeight: 24px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  label-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 15px
    fontWeight: '700'
    lineHeight: 20px
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 13px
    fontWeight: '700'
    lineHeight: 18px
  label-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 11px
    fontWeight: '700'
    lineHeight: 14px
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 20px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.25rem
  space-xl: 1.75rem
---

## Brand & Style

This design system translates the warmth, friendliness, and tactile joy of the mascot into a high-engagement AI mobile learning experience. Rooted in tactile gamification—inspired by Duolingo's springy feedback, extruded 3D action surfaces, and punchy micro-interactions—the visual environment balances intense functional clarity with playful, rewarding affordances.

### Brand Personality & Core Mood
- **Playful Tactility:** Interactive elements feel physically pressable, rounded, and responsive. Buttons feature extruded 3D undersides that sink on tap, providing satisfying pseudo-haptic feedback.
- **Warm & Encouraging:** Soft cream-white canvas backgrounds combined with marshmallow-like lavender surfaces reduce learning anxiety and cognitive fatigue.
- **Disciplined Gamification:** While buttons and reward cards exhibit rich volumetric physical presence, learning typography, code snippets, and review flows remain distraction-free, crisp, and legible.

### Visual Architecture
The system employs an **App-like Web Frame** optimized for handheld mobile ergonomics (max-width `440px` centered on larger displays, viewport height anchored `100dvh`). It avoids dark mode entirely for its primary launch state to preserve the cheerful, bright cafe-and-marshmallow character world.

## Colors

The color palette directly references the 3D mascot cup, the marshmallow character, and playful ed-tech reward states.

- **Primary (`#7C4DFF` / `#8C6BF7`):** The signature vibrant lavender-purple of the mascot cup. Used for high-intent primary CTA buttons, active state highlights, and focus borders.
- **Secondary (`#6B46C1` / `#4C2882`):** The deeper purple core. Serves as the extruded 3D base depth of primary buttons, active bottom tab icon indicators, and prominent section titles.
- **Tertiary & Gamification (`#FF9F1C` Streak Amber / `#2EC4B6` Mint):** Warm fire amber powers streak indicators, review milestones, and celebration triggers, while mint provides instant positive validation on review questions.
- **Canvas & Neutrals (`#FAF8FF` / `#261E35`):** The canvas avoids clinical stark whites in favor of soft cream lavender (`#FAF8FF`). High-contrast typography sits comfortably on `#261E35` to guarantee AAA readability on code blocks, definitions, and flashcards.
- **Button Layering Pairs:** Every action button uses a two-tone pair to create extruded depth:
  - Primary: Face `#7C4DFF`, Underside Lip `#5835C4`
  - Streak / Action: Face `#FF9F1C`, Underside Lip `#D97B00`
  - Secondary Neutral: Face `#FFFFFF`, Border `#EAE3FC`, Underside Lip `#DDD4F5`

## Typography

The typographic hierarchy prioritizes friendly warmth and immediate readability. **Plus Jakarta Sans** is selected for its rounded humanist contours, generous geometric x-height, and optimistic personality that mirrors Korean geometric favorites like Pretendard Rounded.

- **Headlines & Titles:** Set in Bold (`700`) and ExtraBold (`800`) weights. Letter spacing is kept slightly tightened (`-0.02em`) to produce a cohesive, punchy editorial headline feel that locks into card headers and gamified status modals.
- **Instructional Body:** Set in Regular (`400`) and SemiBold (`600`) at comfortable line heights (`1.45` to `1.5`) to preserve frictionless skimming across learning modules and concept explanations.
- **Technical & LaTeX Content:** Code blocks within lectures are styled using **JetBrains Mono** with rounded inline containers and tinted lavender backgrounds to sit comfortably alongside playful UI tokens.

## Layout & Spacing

### Mobile-First Constraint & Layout Model
The layout enforces a strictly contained mobile viewport architecture (`max-width: 440px`) centered horizontally in desktop browsers with an ambient outer shadow.

- **Horizontal Canvas Rhythm:** A persistent outer screen margin of `16px` (`margin: 1rem`) provides consistent gutter alignment across all card lists, headers, and bottom-sheet controls.
- **Vertical Rhythm (Home Feed):** The home screen adheres to the specified 5-tier vertical content stack below the streak header:
  1. `Today Review Queue` (Hero card)
  2. `Continue Learning` (Horizontal progress card)
  3. `Pending Tasks` (Action list)
  4. `Generating / Status Items` (Pill status alerts)
  5. `Create / Explore Gateway` (Large tactile entry button)
- **Top and Bottom Frame Pinning:**
  - **Top Bar:** Fixed `56px` height with safe-area support, housing page title and circular back buttons.
  - **Bottom Navigation Bar:** Fixed `68px` height (excluding device home bar padding), accommodating the 5 dense tabs (`홈`, `코스`, `로드맵`, `복습`, `내 정보`) with equal-distribution flex columns.

## Elevation & Depth

This system avoids blurred, hazy realistic dropshadows. Depth is articulated purely through **tactile extrusion (3D offset bevels)** and **crisp 2px tinted borders**, matching Duolingo’s signature physical game style:

- **Tactile 3D Buttons:** Buttons feature a solid bottom lip created with an extra solid box-shadow or bottom border (`box-shadow: 0 4px 0 0 var(--underside-color)`). When tapped (`:active`), the element translates downwards (`transform: translateY(3px)`), flattening the extrusion to `0 1px 0 0`, simulating physical spring resistance.
- **Card Surfaces:** White cards (`#FFFFFF`) feature a uniform `2px solid #EAE3FC` stroke and a resting extrusion drop of `box-shadow: 0 4px 0 0 #E2D9FA`. They rest visibly above the `#FAF8FF` backdrop.
- **Floating Modals & Sheets:** Overlays pop in from bottom safe margins with crisp `2px solid #D6C7FF` borders and an offset base of `0 8px 0 0 rgba(76, 40, 130, 0.12)`.

## Shapes

The shape system is friendly, bubbly, and ergonomic:
- **Base Components:** Radius `16px` (`rounded-lg`) for primary action cards, study flashcards, and input wrappers.
- **Pill Elements:** Full pill radius (`9999px`) for interactive chips, streak counters, bottom tab selection bubbles, and action buttons.
- **Volumetric Buttons:** Minimum radius `16px` to `20px` to maintain a marshmallow-like, squishy button silhouette.

## Components

### 1. Volumetric Tactile Buttons
- **Primary 3D Button:** 
  - Background: `#7C4DFF`
  - Typography: `16px`, Bold `800`, `#FFFFFF`
  - Shape: Rounded `16px`, height `52px`
  - Shadow & Depth: `box-shadow: 0 4px 0 0 #5835C4`, border `none`
  - Pressed State: `transform: translateY(3px)`, `box-shadow: 0 1px 0 0 #5835C4`
- **Secondary White Button:**
  - Background: `#FFFFFF`
  - Border: `2px solid #EAE3FC`
  - Shadow & Depth: `box-shadow: 0 4px 0 0 #DDD4F5`
  - Pressed State: `transform: translateY(3px)`, `box-shadow: 0 1px 0 0 #DDD4F5`

### 2. Gamified Bottom Navigation Bar (5-Tab)
- Container: Height `68px`, background `#FFFFFF`, border-top `2px solid #EAE3FC`.
- Item: Equal 5-column grid. Icons are chunky vector strokes with rounded terminals.
- Active State: Selected tab icon and label animate with a brief spring scale up (`scale(1.08)`), tinted in `#7C4DFF` with a soft lavender bubble badge (`#F1ECFE`).

### 3. Study & Flashcard Component (Review Tab)
- Surface: `#FFFFFF` card, `2px solid #EAE3FC`, `box-shadow: 0 6px 0 0 #E2D9FA`, border-radius `24px`.
- Interaction: Single-card view without nested navigation. Tapping card triggers 3D flip revealing answer and 4-tier tactile rating buttons:
  - `다시` (Red Coral: `#FF5964`, lip `#D63031`)
  - `어려움` (Amber: `#FF9F1C`, lip `#D97B00`)
  - `좋음` (Primary Purple: `#7C4DFF`, lip `#5835C4`)
  - `쉬움` (Mint: `#2EC4B6`, lip `#1B998B`)

### 4. Streak Indicator & Status Badges
- Shape: Pill radius `9999px`, padding `6px 14px`.
- Streak Badge: Surface `#FFF6E9`, border `1.5px solid #FF9F1C`, flame icon paired with Tertiary bold counter (`#D97B00`).

### 5. Input Fields & Checkbox
- **Input Fields:** Height `52px`, background `#FAF8FF`, border `2px solid #EAE3FC`, text `#261E35`. Focus state triggers `2px solid #7C4DFF` and `box-shadow: 0 2px 0 0 #7C4DFF`.
- **Roadmap Checkbox:** Bouncy `24px x 24px` squircle. Unchecked has a `2px solid #D6C7FF` border. Checked transitions to `#7C4DFF` fill with a crisp white checkmark icon and slight pop scaling.

### 6. Waiting & Generation Screen
- Central Mascot Float: Floating cup mascot with pulsing marshmallow breathing keyframe.
- Text: Bold headline with animated ellipsis, accompanied by a pill progress bar with striped lavender animation.