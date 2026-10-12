import { QueryClientProvider, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { createAuthQueryClient } from '../api/queryClient'
import { redirectToLogin, rememberReturnPath, takeReturnPath } from '../lib/authNavigation'
import LoginPage from '../pages/LoginPage'
import RequireAuth from './RequireAuth'

const me = { email: 'admin@example.com', status: 'ACTIVE', role: 'ADMIN', termsAgreed: false, waitlistPosition: null }

function ProtectedContent() {
  const client = useQueryClient()
  const [enabled, setEnabled] = useState(false)
  useQuery({ queryKey: ['other'], queryFn: () => apiFetch('/other'), enabled })
  const mutation = useMutation({ mutationFn: () => apiFetch('/change', { method: 'POST' }) })
  return <>
    <h1>보호된 화면</h1>
    <button onClick={() => void client.invalidateQueries({ queryKey: ['me'] })}>사용자 다시 확인</button>
    <button onClick={() => setEnabled(true)}>다른 조회</button>
    <button onClick={() => mutation.mutate()}>변경 요청</button>
  </>
}

function TestRoutes() {
  const navigate = useNavigate()
  const [client] = useState(() => createAuthQueryClient(() => redirectToLogin(navigate)))
  return <QueryClientProvider client={client}>
    <Routes>
      <Route element={<RequireAuth />}>
        <Route path="/" element={<ProtectedContent />} />
        <Route path="/curriculums/:id" element={<ProtectedContent />} />
      </Route>
      <Route path="/login" element={<LoginPage />} />
    </Routes>
  </QueryClientProvider>
}

function renderAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(<BrowserRouter><TestRoutes /></BrowserRouter>)
}

beforeEach(() => {
  sessionStorage.clear()
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(me)))))
})

afterEach(() => vi.unstubAllGlobals())

test('첫 로그인 확인 동안 회색 줄을 보여 준다', async () => {
  let resolve!: (response: Response) => void
  vi.mocked(fetch).mockReturnValue(new Promise((done) => { resolve = done }))
  renderAt('/')
  expect(screen.getByRole('status', { name: '로그인 확인 중' })).toHaveAttribute('aria-busy', 'true')
  expect(screen.queryByRole('heading', { name: '보호된 화면' })).not.toBeInTheDocument()
  await act(async () => resolve(new Response(JSON.stringify(me))))
  expect(await screen.findByRole('heading', { name: '보호된 화면' })).toBeVisible()
})

test('처음 연 주소의 401은 안내 없이 로그인으로, 로그인 성공은 기억한 주소로 한 번 돌아간다', async () => {
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '서버 문구' }), { status: 401 }))
  const first = renderAt('/curriculums/12?tab=1#lesson')
  await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })
  expect(window.location.pathname).toBe('/login')
  expect(screen.getByRole('status')).toBeEmptyDOMElement()
  expect(sessionStorage.getItem('mogum:return-path')).toBe('/curriculums/12?tab=1#lesson')
  expect(fetch).toHaveBeenCalledTimes(1)
  first.unmount()

  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify(me))))
  renderAt('/')
  await waitFor(() => expect(window.location.pathname + window.location.search + window.location.hash).toBe('/curriculums/12?tab=1#lesson'))
  expect(takeReturnPath()).toBeNull()
})

test.each(['//evil.example', 'https://evil.example', '/login', '/login?error=x', '/\\evil.example'])('로그인 성공 뒤 잘못된 주소 %s를 버리고 홈에 남는다', async (path) => {
  rememberReturnPath(path)
  renderAt('/')
  await screen.findByRole('heading', { name: '보호된 화면' })
  expect(window.location.pathname).toBe('/')
  expect(takeReturnPath()).toBeNull()
})

test.each(['사용자 다시 확인', '다른 조회', '변경 요청'])('확인 성공 뒤 %s의 401은 같은 로그인 만료 안내로 보낸다', async (button) => {
  renderAt('/curriculums/12?tab=1#lesson')
  await screen.findByRole('heading', { name: '보호된 화면' })
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '서버 문구' }), { status: 401 })))
  fireEvent.click(screen.getByRole('button', { name: button }))
  await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })
  expect(window.location.pathname).toBe('/login')
  expect(screen.getByRole('status')).toHaveTextContent('다시 로그인해 주세요.')
  expect(sessionStorage.getItem('mogum:return-path')).toBe('/curriculums/12?tab=1#lesson')
  expect(fetch).toHaveBeenCalledTimes(2)
})

test('로그인 확인의 RATE_LIMITED는 요청이 잦다는 안내를 표시한다', async () => {
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'RATE_LIMITED', message: '서버 내부 문구' }), { status: 429 })))
  renderAt('/')
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('잠시 후 다시 시도해 주세요.'))
  expect(screen.queryByText('서버 내부 문구')).not.toBeInTheDocument()
  expect(screen.queryByText('불러오지 못했어요.')).not.toBeInTheDocument()
  expect(window.location.pathname).toBe('/')
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify(me))))
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('heading', { name: '보호된 화면' })).toBeVisible()
})

test('다시 시도는 응답을 기다리는 동안 잠겨 추가 요청을 보내지 않는다', async () => {
  renderAt('/')
  await screen.findByRole('heading', { name: '보호된 화면' })
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'RATE_LIMITED', message: '서버 문구' }), { status: 429 }))
  fireEvent.click(screen.getByRole('button', { name: '사용자 다시 확인' }))
  const button = await screen.findByRole('button', { name: '다시 시도' })
  expect(button).toBeEnabled()

  let resolve!: (response: Response) => void
  vi.mocked(fetch).mockImplementation(() => new Promise((done) => { resolve = done }))
  fireEvent.click(button)
  await waitFor(() => expect(button).toBeDisabled())
  expect(button).toHaveTextContent('다시 시도')
  fireEvent.click(button)
  expect(fetch).toHaveBeenCalledTimes(3)

  await act(async () => resolve(new Response(JSON.stringify({ code: 'RATE_LIMITED', message: '서버 문구' }), { status: 429 })))
  await waitFor(() => expect(button).toBeEnabled())
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify(me))))
  fireEvent.click(button)
  expect(await screen.findByRole('heading', { name: '보호된 화면' })).toBeVisible()
})

test.each(['네트워크', '서버'])('로그인 확인의 %s 실패는 불러오기 실패 안내와 다시 시도를 표시한다', async (failure) => {
  if (failure === '네트워크') {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'))
  } else {
    vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: '서버 내부 문구' }), { status: 500 })))
  }
  renderAt('/')
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('불러오지 못했어요.'), { timeout: 5000 })
  expect(screen.queryByText('잠시 후 다시 시도해 주세요.')).not.toBeInTheDocument()
  expect(screen.queryByText('서버 내부 문구')).not.toBeInTheDocument()
  expect(screen.queryByText('Failed to fetch')).not.toBeInTheDocument()
  expect(window.location.pathname).toBe('/')
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify(me))))
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('heading', { name: '보호된 화면' })).toBeVisible()
})
