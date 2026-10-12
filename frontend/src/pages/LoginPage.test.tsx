import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import { googleLoginUrl } from '../api/client'
import LoginPage from './LoginPage'

afterEach(() => vi.unstubAllEnvs())

test.each([
  ['?error=login_failed', '로그인하지 못했어요. 다시 시도해 주세요.'],
  ['?error=not_allowed', '지금은 공개 전이라 정해진 계정만 들어올 수 있어요.'],
  ['?error=unknown', '로그인하지 못했어요. 다시 시도해 주세요.'],
  ['?error=', '로그인하지 못했어요. 다시 시도해 주세요.'],
])('로그인 콜백 %s 안내를 읽어 준다', (search, message) => {
  render(<MemoryRouter initialEntries={[`/login${search}`]}><LoginPage /></MemoryRouter>)
  const notice = screen.getByRole('status')
  expect(notice).toHaveTextContent(message)
  expect(notice).toHaveAttribute('aria-live', 'polite')
  expect(notice).toHaveAttribute('aria-atomic', 'true')
  expect(notice).toHaveClass('bg-surface', 'border', 'border-line', 'rounded-md', 'px-4', 'py-3', 'text-base', 'text-ink', 'mt-6')
  expect(notice).not.toHaveClass('text-danger')
  expect(notice).toHaveFocus()
  expect(document.title).toBe('로그인 · 머금')
})

test.each(['', 'https://api.mogum.example'])('기본 안내는 비어 있고 출처 %s의 Google 로그인 링크를 표시한다', (origin) => {
  vi.stubEnv('VITE_API_BASE_URL', origin)
  render(<MemoryRouter initialEntries={['/login']}><LoginPage /></MemoryRouter>)
  expect(screen.getByText('머금', { selector: 'p' })).toBeVisible()
  expect(screen.queryByRole('heading', { name: '머금' })).not.toBeInTheDocument()
  expect(screen.getByText('배운 건 머금고, 흔들리는 건 다시 한 모금')).toBeVisible()
  expect(screen.getByText('주제만 정하면 AI가 강의를 짜고 만들어요. 복습 결과를 보고 이미 아는 건 짧게, 흔들리는 건 자세히 다음 강의를 만들어요.')).toBeVisible()
  const heading = screen.getByRole('heading', { name: '배운 걸 잊지 않게, 복습이 다음 강의를 바꾸는 학습', level: 1 })
  expect(heading).toBeVisible()
  expect(heading).toHaveFocus()
  expect(screen.getByRole('status')).toBeEmptyDOMElement()
  expect(screen.getByRole('status')).not.toHaveClass('border')
  expect(screen.getByRole('status')).not.toHaveClass('bg-surface')
  expect(screen.getByRole('status')).not.toHaveClass('mt-6')
  const link = screen.getByRole('link', { name: 'Google 계정으로 계속하기' })
  expect(link).toHaveAttribute('href', googleLoginUrl())
  expect(link).toHaveAttribute('href', `${origin}/auth/google`)
  expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  expect(document.title).toBe('로그인 · 머금')
})

test('세션이 만료되면 같은 알림 카드에 안내를 읽어 주고 포커스를 옮긴다', () => {
  render(<MemoryRouter initialEntries={[{ pathname: '/login', state: { sessionExpired: true } }]}><LoginPage /></MemoryRouter>)
  const notice = screen.getByRole('status')
  expect(notice).toHaveTextContent('다시 로그인해 주세요.')
  expect(notice).toHaveClass('bg-surface', 'border', 'border-line', 'rounded-md', 'px-4', 'py-3', 'text-base', 'text-ink', 'mt-6')
  expect(notice).not.toHaveClass('text-danger')
  expect(notice).toHaveAttribute('aria-live', 'polite')
  expect(notice).toHaveAttribute('aria-atomic', 'true')
  expect(notice).toHaveFocus()
  expect(document.title).toBe('로그인 · 머금')
})

test('공개 때 추가할 가입 안내와 정원 안내, 약관 링크는 표시하지 않는다', () => {
  render(<MemoryRouter initialEntries={['/login']}><LoginPage /></MemoryRouter>)
  expect(screen.queryByText('처음이면 가입이 함께 진행돼요.')).not.toBeInTheDocument()
  expect(screen.queryByText(/^지금은 정원을 정해 받고 있어요\./)).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '이용약관' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '개인정보처리방침' })).not.toBeInTheDocument()
})

test('세션 만료 안내보다 콜백 오류를 먼저 표시한다', () => {
  render(<MemoryRouter initialEntries={[{ pathname: '/login', search: '?error=not_allowed', state: { sessionExpired: true } }]}><LoginPage /></MemoryRouter>)
  expect(screen.getByRole('status')).toHaveTextContent('지금은 공개 전이라 정해진 계정만 들어올 수 있어요.')
})
