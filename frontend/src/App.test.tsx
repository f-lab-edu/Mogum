import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'

const homeResponses: Record<string, unknown> = {
  '/api/home/streak': { days: 0, filledToday: false },
  '/api/home/reviews': { remainingCount: 0 },
  '/api/home/continue-learning': {},
  '/api/home/todos': { items: [] },
  '/api/home/jobs': { jobs: [] },
}
function stubFetch(mock: (path: string, options?: RequestInit) => Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path, options) => path in homeResponses
    ? Promise.resolve(new Response(JSON.stringify({ data: homeResponses[path] }))) : mock(path, options)))
}

test('작업 주소를 직접 열면 승인 뒤 작업을 읽고 기다리는 화면을 표시한다', async () => {
  window.history.replaceState(null, '', '/jobs/12')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 12, type: 'MATERIAL', status: 'RUNNING', subject: { title: '자료를 만들 강의' } } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '자료를 만들 강의' })).toBeVisible()
  expect(screen.getByRole('status')).toHaveTextContent('강의 자료를 만들고 있어요')
  expect(fetch).toHaveBeenCalledWith('/api/jobs/12', expect.objectContaining({ method: 'GET' }))
})

test('강의 주소는 승인 뒤 실제 강의와 본문을 표시한다', async () => {
  window.history.replaceState(null, '', '/courses/31/lessons/51')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 51, courseId: 31, courseTitle: '자바 기초', position: 1, title: '변수', completed: false, material: { body: '값을 담아요.' } } })))))
  render(<App />)
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '1강 · 변수' })).toBeVisible()
    expect(screen.getByText('값을 담아요.')).toBeVisible()
    expect(screen.getByRole('heading', { name: '강의' })).toHaveFocus()
  })
})

test('축하 주소는 승인 뒤 코스 제목과 홈으로를 표시한다', async () => {
  window.history.replaceState(null, '', '/courses/31/done')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 31, title: '자바 기초', status: 'COMPLETED' } })))))
  render(<App />)
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '자바 기초 코스를 마쳤어요. 고생했어요!' })).toBeVisible()
    expect(screen.getByRole('button', { name: '홈으로' })).toBeEnabled()
  })
})

test.each(['/new', '/jobs/12', '/courses/31', '/roadmaps/41', '/courses/31/lessons/51', '/courses/31/done'])('승인 전에는 %s에서 작업이나 결과를 읽지 않는다', async (path) => {
  window.history.replaceState(null, '', path)
  stubFetch(vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status: 'APPLIED', name: '민서', email: 'minseo@example.com' } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '가입 승인을 기다리고 있어요' })).toBeVisible()
  expect(fetch).toHaveBeenCalledTimes(1)
})

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

test('/에서 로그인 확인 뒤 홈의 스트릭과 오늘 복습·새로 배우기를 표시한다', async () => {
  window.history.replaceState(null, '', '/')
  stubFetch(vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { email: 'admin@example.com', status: 'APPROVED', name: '민서' } }))))

  render(<App />)

  await screen.findByText('오늘 복습할 카드가 없어요')
  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: '머금' })).toHaveFocus()
    expect(screen.getByText('오늘 복습 1장이나 강의 하나로 시작해요')).toBeVisible()
    expect(screen.getByRole('link', { name: '새로 배우기' })).toHaveAttribute('href', '/new')
    expect(document.title).toBe('홈 · 머금')
  })
  expect(fetch).toHaveBeenCalledWith('/api/me', expect.objectContaining({ credentials: 'include' }))
})

test('/의 401은 실제 앱에서도 U1으로 보내고 처음 온 사람에게 만료 문구를 보이지 않는다', async () => {
  window.history.replaceState(null, '', '/?tab=1#home')
  stubFetch(vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '서버 문구' }), { status: 401 })))
  render(<App />)
  expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
  expect(window.location.pathname).toBe('/login')
  expect(screen.getByRole('status')).toBeEmptyDOMElement()
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('/login은 로그인 확인을 거치지 않는다', () => {
  window.history.replaceState(null, '', '/login')
  stubFetch(vi.fn())
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
    stubFetch(vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      data: { status, email: 'minseo@example.com', name: '민서', futureField: '무시할 칸' },
    })))))
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeVisible()
    await waitFor(() => expect(window.location.pathname).toBe(path))
    expect(screen.queryByText('무시할 칸')).not.toBeInTheDocument()
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(status === 'APPROVED' ? 6 : 1))
  },
)

test('모르는 상태는 승인 화면이나 원래 값을 표시하지 않고 기존 불러오기 실패로 처리한다', async () => {
  window.history.replaceState(null, '', '/')
  stubFetch(vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
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

test.each(tabPages)('승인된 사람은 $entry 주소에서 틀 안의 $label 화면을 본다', async ({ entry, title, label }) => {
  window.history.replaceState(null, '', entry)
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/courses' ? { courses: [] } : path === '/api/roadmaps' ? { roadmaps: [] } : path === '/api/reviews/today' ? { cards: [], remainingCount: 0 } : { status: 'APPROVED', name: '민서', email: 'minseo@example.com' } })))))
  render(<App />)
  const heading = await screen.findByRole('heading', { level: 1, name: title })
  expect(screen.getByRole('main')).toContainElement(heading)
  if (entry === '/courses' || entry === '/roadmaps') {
    expect(await screen.findByText(entry === '/courses' ? '아직 만든 코스가 없어요' : '아직 만든 로드맵이 없어요')).toBeVisible()
  } else if (entry === '/review') {
    expect(await screen.findByText('오늘 복습할 카드가 없어요')).toBeVisible()
  } else if (entry === '/') {
    await screen.findByText('오늘 복습할 카드가 없어요')
    await waitFor(() => {
      expect(screen.getByText('오늘 복습 1장이나 강의 하나로 시작해요')).toBeVisible()
      expect(screen.getByRole('link', { name: '새로 배우기' })).toHaveAttribute('href', '/new')
    })
  } else expect(screen.getByRole('main').textContent).toBe(title)
  if (entry !== '/') expect(screen.queryByRole('region', { name: '스트릭' })).not.toBeInTheDocument()
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
    stubFetch(vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: { status, name: '민서', email: 'minseo@example.com' } })))))
    render(<App />)
    expect(await screen.findByRole('heading', { name: heading })).toBeVisible()
    await waitFor(() => expect(window.location.pathname).toBe(path))
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  },
)

test('홈에서 탭 링크로 다른 자리 화면을 열어도 계정 화면으로 되돌리지 않는다', async () => {
  window.history.replaceState(null, '', '/')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/courses' ? { courses: [] } : path === '/api/roadmaps' ? { roadmaps: [] } : path === '/api/reviews/today' ? { cards: [], remainingCount: 0 } : { status: 'APPROVED', name: '민서', email: 'minseo@example.com' } })))))
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
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
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

test('코스 주소를 직접 열면 승인 뒤 실제 코스를 읽고 같은 주소에 표시한다', async () => {
  window.history.replaceState(null, '', '/courses/31')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 31, status: 'REVIEWING', title: '자바 기초', lessons: [], progress: { completedLessons: 0, totalLessons: 0 } } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '자바 기초' })).toBeVisible()
  expect(screen.getByText('검토 중')).toBeVisible()
  expect(fetch).toHaveBeenCalledWith('/api/courses/31', expect.objectContaining({ method: 'GET' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: '코스' })).toHaveFocus())
  expect(window.location.pathname).toBe('/courses/31')
})


test('로드맵 주소를 직접 열면 승인 뒤 실제 계획을 읽는다', async () => {
  window.history.replaceState(null, '', '/roadmaps/41')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { id: 41, status: 'REVIEWING', title: '자바 로드맵', description: '기초부터 실무까지', courses: [] } })))))
  render(<App />)
  expect(await screen.findByRole('heading', { name: '자바 로드맵' })).toBeVisible()
  expect(fetch).toHaveBeenCalledWith('/api/roadmaps/41', expect.objectContaining({ method: 'GET' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: '로드맵' })).toHaveFocus())
})

test.each([false, true])('코스에서 처음 연 강의를 만들고 읽은 뒤 완료하면 다 마침 %s에 맞게 이동한다', async (lastLesson) => {
  window.history.replaceState(null, '', '/courses/31')
  let materialReady = false
  let completed = false
  const lessons = [
    { id: 51, position: 1, title: '변수', description: '값을 담아요', concepts: [], completed: false },
    ...(!lastLesson ? [{ id: 52, position: 2, title: '반복문', description: '여러 번 실행해요', concepts: [], completed: false }] : []),
  ]
  stubFetch(vi.fn().mockImplementation((path, options) => {
    let data: unknown
    if (path === '/api/me') data = { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    else if (path === '/api/courses/31') data = {
      id: 31, title: '자바 기초', status: completed && lastLesson ? 'COMPLETED' : 'LEARNING',
      lessons: lessons.map((lesson) => ({ ...lesson, completed: completed && lesson.id === 51 })),
      progress: { completedLessons: completed ? 1 : 0, totalLessons: lessons.length },
      nextLessonId: completed ? (lastLesson ? undefined : 52) : 51,
    }
    else if (path === '/api/lessons/51/complete') {
      completed = true
      data = { courseId: 31, courseCompleted: lastLesson }
    } else if (path === '/api/lessons/51') data = {
      id: 51, courseId: 31, courseTitle: '자바 기초', position: 1, title: '변수', completed,
      ...(materialReady ? { material: { body: '값을 담는 변수예요.' } } : {}),
    }
    else if (path === '/api/jobs' && options.method === 'POST') data = { id: 77, type: 'MATERIAL', status: 'QUEUED', subject: { title: '변수' } }
    else if (path === '/api/jobs/77') {
      materialReady = true
      data = { id: 77, type: 'MATERIAL', status: 'SUCCEEDED', subject: { title: '변수' }, result: { type: 'LESSON', id: 51, courseId: 31 } }
    } else throw new Error(`Unexpected test request: ${path}`)
    return Promise.resolve(new Response(JSON.stringify({ data })))
  }))
  render(<App />)
  const next = await screen.findByRole('region', { name: '다음에 열 강의' })
  fireEvent.click(within(next).getByRole('link', { name: '1강 · 변수' }))
  await waitFor(() => {
    expect(screen.getByText('값을 담는 변수예요.')).toBeVisible()
    expect(window.location.pathname).toBe('/courses/31/lessons/51')
    expect(vi.mocked(fetch).mock.calls.filter(([path, options]) => path === '/api/jobs' && options?.method === 'POST')).toHaveLength(1)
  })
  fireEvent.click(screen.getByRole('button', { name: '학습 완료' }))
  await waitFor(() => {
    expect(window.location.pathname).toBe(lastLesson ? '/courses/31/done' : '/courses/31')
    if (lastLesson) expect(screen.getByRole('heading', { name: '자바 기초 코스를 마쳤어요. 고생했어요!' })).toBeVisible()
    else {
      expect(screen.getByRole('region', { name: '다음에 열 강의' })).toHaveTextContent('2강 · 반복문')
      expect(screen.getByRole('progressbar', { name: '학습 진도' })).toHaveAttribute('aria-valuenow', '1')
    }
  })
})


test('실제 탭 바에서 복습을 열면 첫 질문을 바로 보이고 떠났다 돌아오면 답을 닫고 새로 읽는다', async () => {
  window.history.replaceState(null, '', '/')
  let reads = 0
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { cards: [{ id: ++reads, question: `질문 ${reads}`, answer: '답이에요', nextIntervalsSeconds: { AGAIN: 60, HARD: 600, GOOD: 86400, EASY: 259200 } }], remainingCount: 1 } })))))
  render(<App />)
  await screen.findByRole('heading', { name: '머금' })
  const tabs = () => screen.getByRole('navigation', { name: '주요 화면 (모바일)' })
  fireEvent.click(within(tabs()).getByRole('link', { name: '복습' }))
  expect(await screen.findByText('질문 1')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '답 보기' }))
  expect(screen.getByText('답이에요')).toBeVisible()
  fireEvent.click(within(tabs()).getByRole('link', { name: '홈' }))
  await screen.findByRole('heading', { name: '머금' })
  fireEvent.click(within(tabs()).getByRole('link', { name: '복습' }))
  expect(await screen.findByText('질문 2')).toBeVisible()
  expect(screen.queryByText('답이에요')).not.toBeInTheDocument()
  expect(screen.queryByText('질문 1')).not.toBeInTheDocument()
})

test.each(['LEARNING', 'COMPLETED'])('%s 코스의 복습 링크를 누르면 실제 복습 화면이 그 코스로 거른 카드를 읽는다', async (status) => {
  window.history.replaceState(null, '', '/courses/31')
  stubFetch(vi.fn().mockImplementation((path) => {
    let data: unknown
    if (path === '/api/me') data = { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    else if (path === '/api/courses/31') data = {
      id: 31, status, title: '자바 기초', todayReviewCount: 3, lessons: [], progress: { completedLessons: 0, totalLessons: 0 },
    }
    else if (path === '/api/reviews/today?courseId=31') data = {
      cards: [1, 2, 3].map((id) => ({ id, question: `변수 질문 ${id}`, answer: '값을 담아요', nextIntervalsSeconds: { AGAIN: 60, HARD: 600, GOOD: 86400, EASY: 259200 } })),
      remainingCount: 3, courseTitle: '자바 기초',
    }
    else throw new Error(`Unexpected test request: ${path}`)
    return Promise.resolve(new Response(JSON.stringify({ data })))
  }))
  render(<App />)
  fireEvent.click(await screen.findByRole('link', { name: '이 코스 복습하기 · 3장' }))
  expect(await screen.findByRole('heading', { name: '변수 질문 1' })).toBeVisible()
  expect(screen.getByText('자바 기초')).toBeVisible()
  expect(screen.getByText('3장 남음')).toBeVisible()
  expect(window.location.pathname + window.location.search).toBe('/review?courseId=31')
  expect(fetch).toHaveBeenCalledWith('/api/reviews/today?courseId=31', expect.objectContaining({ method: 'GET' }))
  expect(fetch).not.toHaveBeenCalledWith('/api/reviews/today', expect.anything())
})

test('거른 복습에서 복습 탭을 누르면 쿼리를 지우고 같은 경로에서도 질문·답·남은 수를 새로 시작한다', async () => {
  window.history.replaceState(null, '', '/review?courseId=31')
  stubFetch(vi.fn().mockImplementation((path) => Promise.resolve(new Response(JSON.stringify({ data: path === '/api/me'
    ? { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }
    : { cards: [{ id: 1, question: path.includes('?') ? '거른 질문' : '전체 질문', answer: '거른 답', nextIntervalsSeconds: { AGAIN: 60, HARD: 600, GOOD: 86400, EASY: 259200 } }], remainingCount: 1, ...(path.includes('?') ? { courseTitle: '자바 기초' } : {}) } })))))
  render(<App />)
  expect(await screen.findByText('거른 질문')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '답 보기' }))
  expect(screen.getByText('거른 답')).toBeVisible()
  fireEvent.click(within(screen.getByRole('navigation', { name: '주요 화면 (모바일)' })).getByRole('link', { name: '복습' }))
  expect(await screen.findByText('전체 질문')).toBeVisible()
  expect(window.location.search).toBe('')
  expect(screen.queryByText('거른 답')).not.toBeInTheDocument()
  expect(screen.queryByText('자바 기초')).not.toBeInTheDocument()
  expect(screen.getByText('1장 남음')).toBeVisible()
})
