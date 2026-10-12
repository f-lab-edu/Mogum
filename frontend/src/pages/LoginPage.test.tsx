import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import LoginPage from './LoginPage'

afterEach(() => vi.unstubAllEnvs())

test.each([
  ['?error=login_failed', '로그인하지 못했어요. 다시 시도해 주세요.'],
  ['?error=not_allowed', '지금은 공개 전이라 정해진 계정만 들어올 수 있어요.'],
  ['?error=unknown', '로그인하지 못했어요. 다시 시도해 주세요.'],
  ['?error=', '로그인하지 못했어요. 다시 시도해 주세요.'],
])('로그인 콜백 %s 안내를 읽어 준다', (search, message) => {
  render(<MemoryRouter initialEntries={[`/login${search}`]}><LoginPage /></MemoryRouter>)
  expect(screen.getByRole('status')).toHaveTextContent(message)
  expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  expect(screen.getByRole('status')).toHaveFocus()
  expect(document.title).toBe('로그인 · 머금')
})

test.each(['', 'https://api.mogum.example'])('기본 안내는 비어 있고 출처 %s의 Google 로그인 링크를 표시한다', (origin) => {
  vi.stubEnv('VITE_API_BASE_URL', origin)
  render(<MemoryRouter initialEntries={['/login']}><LoginPage /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: '머금', level: 1 })).toBeVisible()
  expect(screen.getByRole('heading', { name: '머금', level: 1 })).toHaveFocus()
  expect(screen.getByRole('status')).toBeEmptyDOMElement()
  expect(screen.getByRole('link', { name: 'Google 계정으로 계속하기' })).toHaveAttribute('href', `${origin}/auth/google`)
})

test('세션 만료 안내보다 콜백 오류를 먼저 표시한다', () => {
  render(<MemoryRouter initialEntries={[{ pathname: '/login', search: '?error=not_allowed', state: { sessionExpired: true } }]}><LoginPage /></MemoryRouter>)
  expect(screen.getByRole('status')).toHaveTextContent('지금은 공개 전이라 정해진 계정만 들어올 수 있어요.')
})
