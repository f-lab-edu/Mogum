import { render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

test('/에서 실제 앱의 라우터와 QueryClientProvider를 거쳐 로그인 확인 뒤 머금 제목을 표시한다', async () => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ email: 'admin@example.com', status: 'ACTIVE', role: 'ADMIN', termsAgreed: false, waitlistPosition: null }))))

  render(<App />)

  expect(await screen.findByRole('heading', { level: 1, name: '머금' })).toBeVisible()
  expect(fetch).toHaveBeenCalledWith('/api/me', expect.objectContaining({ credentials: 'include' }))
})

test('/의 401은 실제 앱에서도 U1으로 보내고 처음 온 사람에게 만료 문구를 보이지 않는다', async () => {
  window.history.replaceState(null, '', '/?tab=1#home')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '서버 문구' }), { status: 401 })))
  render(<App />)
  expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
  expect(window.location.pathname).toBe('/login')
  expect(screen.getByRole('status')).toBeEmptyDOMElement()
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('/login은 로그인 확인을 거치지 않는다', () => {
  window.history.replaceState(null, '', '/login')
  vi.stubGlobal('fetch', vi.fn())
  render(<App />)
  expect(screen.getByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
  expect(fetch).not.toHaveBeenCalled()
})
