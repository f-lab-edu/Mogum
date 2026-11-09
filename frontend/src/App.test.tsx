import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

test('/에서 실제 앱의 라우터와 QueryClientProvider를 거쳐 로그인 확인 뒤 머금 제목을 표시한다', async () => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { email: 'admin@example.com', status: 'APPROVED', name: '민서' } }))))

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

const states = [
  { status: 'NOT_APPLIED', path: '/apply', heading: '머금 이용 신청' },
  { status: 'APPLIED', path: '/waiting-approval', heading: '가입 승인을 기다리고 있어요' },
  { status: 'REJECTED', path: '/rejected', heading: '이번에는 함께하지 못하게 되었어요' },
  { status: 'APPROVED', path: '/', heading: '머금' },
]

test.each(states.flatMap((state) => ['/', '/apply', '/waiting-approval', '/rejected'].map((entry) => ({ ...state, entry }))))(
  '$status 사용자가 $entry 주소를 열면 $path 주소로 간다', async ({ status, path, heading, entry }) => {
    window.history.replaceState(null, '', entry)
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      data: { status, email: 'minseo@example.com', name: '민서', futureField: '무시할 칸' },
    })))))
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeVisible()
    await waitFor(() => expect(window.location.pathname).toBe(path))
    expect(screen.queryByText('무시할 칸')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  },
)

test('모르는 상태는 승인 화면이나 원래 값을 표시하지 않고 기존 불러오기 실패로 처리한다', async () => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
    data: { status: 'FUTURE_STATUS', email: 'minseo@example.com', name: '민서' },
  })))))
  render(<App />)
  expect(await screen.findByText('불러오지 못했어요.')).toBeVisible()
  expect(screen.queryByText('FUTURE_STATUS')).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '머금' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
})
