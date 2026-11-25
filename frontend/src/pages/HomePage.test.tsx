import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import HomePage from './HomePage'

const empty = {
  streak: { days: 0, filledToday: false },
  reviews: { remainingCount: 0 },
  'continue-learning': {},
  todos: { items: [] },
  jobs: { jobs: [] },
}
const populated = {
  streak: { days: 5, filledToday: false },
  reviews: { remainingCount: 3 },
  'continue-learning': { course: { id: 31, title: '자바 기초', nextLesson: { id: 51, title: '변수' } } },
  todos: { items: [
    { type: 'ROADMAP_NEXT_COURSE', targetId: 42, title: '백엔드 로드맵' },
    { type: 'COURSE_REVIEW', targetId: 32, title: '자료 구조' },
    { type: 'ROADMAP_REVIEW', targetId: 41, title: '네트워크 로드맵' },
  ] },
  jobs: { jobs: [
    { id: 12, type: 'NEW_COURSE', status: 'QUEUED', subject: { topic: '새 코스 주제' } },
    { id: 13, type: 'NEW_ROADMAP', status: 'RUNNING', subject: { topic: '새 로드맵 주제' } },
    { id: 14, type: 'PLAN_ROADMAP_COURSE', status: 'RUNNING', subject: { title: '다음 코스 제목' } },
    { id: 15, type: 'REPLAN_COURSE', status: 'RUNNING', subject: { title: '다시 짜는 코스' } },
    { id: 16, type: 'REPLAN_ROADMAP', status: 'FAILED', subject: { title: '다시 짜는 로드맵' }, error: { code: 'JOB_TEMPORARY_FAILURE', message: '기다리는 화면에서 다룰 문구' } },
    { id: 17, type: 'MATERIAL', status: 'RUNNING', subject: { title: '자료를 만드는 강의' } },
  ] },
}
const clients: QueryClient[] = []
function open(data: Record<string, unknown> = empty, overrides: Record<string, () => Promise<Response>> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    const path = url.replace('/api/home/', '')
    return overrides[path]?.() ?? Promise.resolve(new Response(JSON.stringify({ data: data[path] })))
  }))
  return render(<QueryClientProvider client={client}><MemoryRouter><HomePage /></MemoryRouter></QueryClientProvider>)
}
afterEach(() => { clients.forEach((client) => client.clear()); clients.length = 0; vi.unstubAllGlobals() })

test.each([
  [7, true, '7일 연속 · 오늘 채웠어요'],
  [5, false, '5일 연속 · 복습 1장이나 강의 하나로 이어 가요'],
  [0, false, '오늘 복습 1장이나 강의 하나로 시작해요'],
])('스트릭 %s일·오늘 채움 %s는 정한 문구를 표시한다', async (days, filledToday, message) => {
  open({ ...empty, streak: { days, filledToday } })
  await screen.findByText(message)
  await waitFor(() => expect(screen.getByRole('region', { name: '스트릭' })).toHaveTextContent(message))
})

test('처음 승인된 홈은 스트릭·오늘 복습 0장·새로 배우기만 보이고 제목 포커스와 문서 제목을 유지한다', async () => {
  open()
  await screen.findByText('오늘 복습할 카드가 없어요')
  await waitFor(() => {
    expect(screen.getByText('오늘 복습 1장이나 강의 하나로 시작해요')).toBeVisible()
    expect(screen.getAllByRole('heading').map((heading) => heading.textContent)).toEqual(['머금', '오늘 복습'])
    expect(screen.queryByRole('heading', { name: /이어서 배우기|할 일|만드는 중/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '복습 시작' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(screen.getByRole('link', { name: '새로 배우기' })).toHaveAttribute('href', '/new')
    expect(screen.getByRole('heading', { name: '머금' })).toHaveFocus()
    expect(document.title).toBe('홈 · 머금')
    expect(screen.queryByRole('main')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})

test('오늘 복습 3장은 카드 수와 복습 탭으로 가는 입구를 보인다', async () => {
  open({ ...empty, reviews: populated.reviews })
  await screen.findByRole('link', { name: '복습 시작' })
  await waitFor(() => {
    expect(screen.getByRole('region', { name: '오늘 복습' })).toHaveTextContent('3장')
    expect(screen.getByRole('link', { name: '복습 시작' })).toHaveAttribute('href', '/review')
    expect(screen.queryByText('오늘 복습할 카드가 없어요')).not.toBeInTheDocument()
  })
})

test('스트릭 다음 다섯 칸 순서를 유지하고 서버가 준 항목 순서와 대상 주소를 그대로 쓴다', async () => {
  open(populated)
  await screen.findByRole('link', { name: /자료를 만드는 강의/ })
  await waitFor(() => {
    expect(screen.getAllByRole('region').map((region) => region.getAttribute('aria-label'))).toEqual(['스트릭', '오늘 복습', '이어서 배우기', '할 일', '만드는 중 · 실패'])
    expect(screen.getByRole('link', { name: /자바 기초.*변수/ })).toHaveAttribute('href', '/courses/31/lessons/51')
    const todos = within(screen.getByRole('region', { name: '할 일' })).getAllByRole('link')
    expect(todos.map((link) => link.getAttribute('href'))).toEqual(['/roadmaps/42', '/courses/32', '/roadmaps/41'])
    expect(todos[0]).toHaveTextContent('다음 코스를 만들 수 있어요')
    expect(todos[1]).toHaveTextContent('검토를 기다려요')
    expect(todos[2]).toHaveTextContent('검토를 기다려요')
    const jobs = within(screen.getByRole('region', { name: '만드는 중 · 실패' })).getAllByRole('link')
    expect(jobs.map((link) => link.getAttribute('href'))).toEqual([12, 13, 14, 15, 16, 17].map((id) => `/jobs/${id}`))
    expect(jobs.map((link) => link.textContent)).toEqual([
      '새 코스 주제차례를 기다리고 있어요', '새 로드맵 주제로드맵 계획을 짜고 있어요',
      '다음 코스 제목강의 목록을 짜고 있어요', '다시 짜는 코스강의 목록을 짜고 있어요',
      '다시 짜는 로드맵만들지 못했어요', '자료를 만드는 강의강의 자료를 만들고 있어요',
    ])
    expect(screen.getAllByRole('link').at(-1)).toHaveAttribute('href', '/new')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.queryByText(/%|진도|남은 시간|약 .*분|크레딧|다음에 채워|학습 코치|목표 달성|학습 꿀팁|기억 유지율|예상 소요|JOB_TEMPORARY_FAILURE|기다리는 화면에서 다룰 문구/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '다음 코스 만들기' })).not.toBeInTheDocument()
  })
})

const areas = [
  ['streak', '스트릭'], ['reviews', '오늘 복습'], ['continue-learning', '이어서 배우기'], ['todos', '할 일'], ['jobs', '만드는 중 · 실패'],
] as const
test.each(areas.flatMap(([path, label]) => ['서버', '연결'].map((failure) => ({ path, label, failure }))))('$label의 $failure 오류와 다시 시도는 그 칸에만 영향을 준다', async ({ path, label, failure }) => {
  const serverMessage = '잠시 뒤 다시 확인해 주세요.'
  open(populated, { [path]: () => failure === '연결'
    ? Promise.reject(new TypeError('PRIVATE_NETWORK_DETAILS'))
    : Promise.resolve(new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: serverMessage }), { status: 500 })) })
  await screen.findByRole('alert')
  await waitFor(() => {
    const area = within(screen.getByRole('region', { name: label }))
    expect(area.getByRole('alert')).toHaveTextContent(failure === '서버' ? serverMessage : '연결하지 못했어요. 다시 시도해 주세요.')
    expect(area.getByRole('button', { name: '다시 시도' })).toBeEnabled()
    for (const [, otherLabel] of areas.filter(([other]) => other !== path)) {
      expect(within(screen.getByRole('region', { name: otherLabel })).queryByRole('alert')).not.toBeInTheDocument()
    }
    if (path !== 'streak') expect(screen.getByText('5일 연속 · 복습 1장이나 강의 하나로 이어 가요')).toBeVisible()
    if (path !== 'reviews') expect(screen.getByRole('link', { name: '복습 시작' })).toBeVisible()
    if (path !== 'continue-learning') expect(screen.getByRole('link', { name: /자바 기초.*변수/ })).toBeVisible()
    if (path !== 'todos') expect(screen.getByRole('link', { name: /자료 구조/ })).toBeVisible()
    if (path !== 'jobs') expect(screen.getByRole('link', { name: /자료를 만드는 강의/ })).toBeVisible()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByText(/INTERNAL_ERROR|PRIVATE_NETWORK_DETAILS/)).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(5)
  })
  vi.mocked(fetch).mockImplementation((url) => Promise.resolve(new Response(JSON.stringify({ data: populated[String(url).replace('/api/home/', '') as keyof typeof populated] }))))
  fireEvent.click(within(screen.getByRole('region', { name: label })).getByRole('button', { name: '다시 시도' }))
  await waitFor(() => {
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText('5일 연속 · 복습 1장이나 강의 하나로 이어 가요')).toBeVisible()
    expect(screen.getByRole('link', { name: /자료를 만드는 강의/ })).toBeVisible()
    expect(screen.getByRole('link', { name: /자바 기초.*변수/ })).toBeVisible()
    expect(fetch).toHaveBeenCalledTimes(6)
    for (const [other] of areas) {
      expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === `/api/home/${other}`)).toHaveLength(other === path ? 2 : 1)
    }
  })
})

test('한 칸이 읽는 중이어도 나머지 칸과 새로 배우기 입구를 표시한다', async () => {
  open(populated, { jobs: () => new Promise(() => {}) })
  await screen.findByRole('link', { name: '복습 시작' })
  await waitFor(() => {
    expect(screen.getByRole('region', { name: '만드는 중 · 실패' })).toHaveTextContent('불러오고 있어요')
    expect(within(screen.getByRole('region', { name: '만드는 중 · 실패' })).getByRole('status')).toBeVisible()
    expect(screen.getByRole('link', { name: /자바 기초.*변수/ })).toBeVisible()
    expect(screen.getByRole('link', { name: /자료 구조/ })).toBeVisible()
    expect(screen.getByRole('link', { name: '새로 배우기' })).toBeVisible()
  })
})

test('아직 읽지 못한 칸은 상태 한 줄씩 표시하고 항상 새로 배우기를 열 수 있다', () => {
  open(empty, Object.fromEntries(areas.map(([path]) => [path, () => new Promise<Response>(() => {})])))
  expect(screen.getAllByRole('status')).toHaveLength(5)
  expect(screen.getByRole('link', { name: '새로 배우기' })).toHaveAttribute('href', '/new')
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
})

test('모르는 할 일 종류·작업 종류·작업 상태와 추가 응답 칸은 노출하지 않는다', async () => {
  open({ ...empty,
    todos: { items: [{ type: 'FUTURE_TODO', title: '미래 할 일', targetId: 99 }] },
    jobs: { jobs: [
      { id: 98, type: 'FUTURE_JOB', status: 'RUNNING', subject: { title: '미래 작업' } },
      { id: 99, type: 'MATERIAL', status: 'FUTURE_STATUS', subject: { title: '미래 상태' } },
    ] },
    streak: { ...empty.streak, futureField: '시스템 상세' },
  })
  await screen.findByText('오늘 복습할 카드가 없어요')
  await waitFor(() => {
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /할 일|만드는 중/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/미래|FUTURE|시스템 상세/)).not.toBeInTheDocument()
  })
})

test('서버가 다음 강의를 생략하면 이어서 배우기 링크와 칸을 숨긴다', async () => {
  open({ ...empty, 'continue-learning': { course: { id: 31, title: '다음 강의 없는 코스' } } })
  await screen.findByText('오늘 복습할 카드가 없어요')
  await waitFor(() => {
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '이어서 배우기' })).not.toBeInTheDocument()
    expect(screen.queryByText('다음 강의 없는 코스')).not.toBeInTheDocument()
  })
})
