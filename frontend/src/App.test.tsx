import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

test('작업 주소를 직접 열면 승인 뒤 작업을 읽고 기다리는 화면을 표시한다', async () => {
  window.history.replaceState(null, '', '/jobs/12')
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 12, type: 'MATERIAL', status: 'RUNNING', subject: { title: '자료를 만들 강의' } } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '자료를 만들 강의' })).toBeVisible()
  expect(screen.getByRole('status')).toHaveTextContent('강의 자료를 만들고 있어요')
  expect(fetch).toHaveBeenCalledWith('/api/jobs/12', expect.objectContaining({ method: 'GET' }))
})

test.each([
  ['/courses/31', '코스'],
  ['/roadmaps/41', '로드맵'],
  ['/courses/31/lessons/51', '강의'],
])('결과 주소 %s는 승인 뒤 제목 %s와 뒤로 가기만 있는 자리를 표시한다', async (path, title) => {
  window.history.replaceState(null, '', path)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status: 'APPROVED', name: '민서', email: 'minseo@example.com' } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: title })).toBeVisible()
  expect(screen.getByRole('main')).toHaveTextContent(/^$/)
  expect(screen.getByRole('button', { name: '뒤로 가기' })).toBeVisible()
  await waitFor(() => expect(screen.getByRole('heading', { name: title })).toHaveFocus())
})

test.each(['/new', '/jobs/12', '/courses/31', '/roadmaps/41', '/courses/31/lessons/51'])('승인 전에는 %s에서 작업이나 결과를 읽지 않는다', async (path) => {
  window.history.replaceState(null, '', path)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status: 'APPLIED', name: '민서', email: 'minseo@example.com' } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '가입 승인을 기다리고 있어요' })).toBeVisible()
  expect(fetch).toHaveBeenCalledTimes(1)
})

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

const tabPages = [
  { entry: '/', title: '머금', label: '홈' },
  { entry: '/courses', title: '코스', label: '코스' },
  { entry: '/roadmaps', title: '로드맵', label: '로드맵' },
  { entry: '/review', title: '복습', label: '복습' },
  { entry: '/profile', title: '내 정보', label: '내 정보' },
]

test.each(tabPages)('승인된 사람은 $entry 주소에서 틀 안의 $label 자리만 본다', async ({ entry, title, label }) => {
  window.history.replaceState(null, '', entry)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status: 'APPROVED', name: '민서', email: 'minseo@example.com' } })))))
  render(<App />)
  const heading = await screen.findByRole('heading', { level: 1, name: title })
  expect(screen.getByRole('main')).toContainElement(heading)
  expect(screen.getByRole('main').textContent).toBe(entry === '/' ? `${title}새로 배우기` : title)
  expect(screen.getByRole('banner')).toBeVisible()
  for (const navigation of screen.getAllByRole('navigation')) {
    expect(within(navigation).getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page')
  }
  await waitFor(() => {
    expect(heading).toHaveFocus()
    expect(document.title).toBe(`${label} · 머금`)
  })
  expect(window.location.pathname).toBe(entry)
})

test.each(states.filter((state) => state.status !== 'APPROVED').flatMap((state) => tabPages.map((tab) => ({ ...state, entry: tab.entry }))))(
  '승인 전 $status 사용자가 $entry 주소를 열어도 계정 화면으로 보내고 틀을 보이지 않는다', async ({ status, path, heading, entry }) => {
    window.history.replaceState(null, '', entry)
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status, name: '민서', email: 'minseo@example.com' } })))))
    render(<App />)
    expect(await screen.findByRole('heading', { name: heading })).toBeVisible()
    await waitFor(() => expect(window.location.pathname).toBe(path))
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  },
)

test('홈에서 탭 링크로 다른 자리 화면을 열어도 계정 화면으로 되돌리지 않는다', async () => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status: 'APPROVED', name: '민서', email: 'minseo@example.com' } })))))
  render(<App />)
  await screen.findByRole('heading', { name: '머금' })
  for (const tab of tabPages.slice(1)) {
    fireEvent.click(within(screen.getByRole('navigation', { name: '주요 화면 (모바일)' })).getByRole('link', { name: tab.label }))
    expect(await screen.findByRole('heading', { name: tab.title })).toBeVisible()
    await waitFor(() => expect(window.location.pathname).toBe(tab.entry))
  }
})

test('임시 홈의 입구에서 새로 배우기로 가고 받기 뒤 실제 기다리는 화면을 연다', async () => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 42, type: 'NEW_COURSE', status: 'QUEUED', subject: { topic: '자바', level: 'BEGINNER', purpose: 'WORK' } } })))))
  render(<App />)
  fireEvent.click(await screen.findByRole('link', { name: '새로 배우기' }))
  await waitFor(() => {
    expect(window.location.pathname).toBe('/new')
    expect(screen.getByRole('heading', { name: '새로 배우기' })).toHaveFocus()
  })
  fireEvent.change(screen.getByRole('textbox', { name: '주제' }), { target: { value: '자바' } })
  fireEvent.click(screen.getByRole('radio', { name: '처음 배워요' }))
  fireEvent.click(screen.getByRole('radio', { name: '업무에 쓰려고요' }))
  fireEvent.click(screen.getByRole('button', { name: '만들기' }))
  expect(await screen.findByRole('heading', { name: '자바' })).toBeVisible()
  await waitFor(() => expect(window.location.pathname).toBe('/jobs/42'))
  expect(screen.getByRole('status')).toHaveTextContent('차례를 기다리고 있어요')
  expect(fetch).toHaveBeenCalledWith('/api/jobs', expect.objectContaining({ method: 'POST' }))
})
