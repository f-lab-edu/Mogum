import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import CoursePage from './CoursePage'

const lessons = [
  { id: 51, position: 1, title: '변수', description: '값에 이름을 붙여요', concepts: [{ name: '변수 선언', definition: '이름과 값을 연결하는 것' }], completed: false },
  { id: 52, position: 2, title: '반복문', description: '여러 번 실행해요', concepts: [{ name: '반복', definition: '같은 일을 되풀이하는 것' }], completed: false },
]
const review = { id: 31, title: '자바 기초', status: 'REVIEWING', lessons, progress: { completedLessons: 0, totalLessons: 2 }, todayReviewCount: 0 }
const learning = { ...review, status: 'LEARNING', roadmapId: 41, nextLessonId: 52, lessons: [{ ...lessons[0], completed: true }, lessons[1]], progress: { completedLessons: 1, totalLessons: 2 } }
const done = { ...learning, status: 'COMPLETED', nextLessonId: undefined, completedAt: '2026-11-17T23:50:00Z', lessons: lessons.map((lesson) => ({ ...lesson, completed: true })), progress: { completedLessons: 2, totalLessons: 2 } }
const job = { id: 77, type: 'REPLAN_COURSE', status: 'QUEUED', subject: { title: review.title } }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function failure(code: string, message: string, fields?: unknown) { return new Response(JSON.stringify({ code, message, fields }), { status: code === 'NOT_FOUND' ? 404 : 409 }) }
function open(data: unknown = review) {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/', element: <h1>홈 자리</h1> },
      { path: '/courses', element: <h1>목록 자리</h1> },
      { path: '/courses/:courseId', element: <AppLayout title="코스"><CoursePage key={31} /></AppLayout> },
      { path: '/jobs/:jobId', element: <h1>기다리는 화면 자리</h1> },
      { path: '/roadmaps/:roadmapId', element: <h1>로드맵 자리</h1> },
      { path: '/courses/:courseId/lessons/:lessonId', element: <h1>강의 자리</h1> },
    ],
  }], { initialEntries: ['/', '/courses/31'] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return { client, router }
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
})

async function replan() {
  fireEvent.click(await screen.findByRole('button', { name: '다시 짜기' }))
  return screen.getByRole('textbox', { name: '고칠 점' })
}

test('검토 중은 제목·모구미·안내·동작·순서 있는 강의 목록과 개념을 보인다', async () => {
  open()
  const title = await screen.findByRole('heading', { name: review.title })
  expect(screen.getByText('검토 중')).toBeVisible()
  expect(screen.getByText('강의는 아직 열 수 없어요. 목록을 살펴보고 시작해 주세요.')).toBeVisible()
  expect(screen.getByRole('button', { name: '이대로 시작' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '다시 짜기' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '코스 제목 고치기' })).toBeEnabled()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  const list = screen.getByRole('list', { name: '강의 목록' })
  const rows = within(list).getAllByRole('listitem')
  expect(rows).toHaveLength(2)
  lessons.forEach((lesson, index) => {
    expect(rows[index]).toHaveTextContent(`${lesson.position}강 · ${lesson.title}`)
    expect(rows[index]).toHaveTextContent(lesson.description)
    expect(rows[index]).toHaveTextContent(lesson.concepts[0].name)
    expect(rows[index]).toHaveTextContent(lesson.concepts[0].definition)
    expect(within(rows[index]).queryByRole('link')).not.toBeInTheDocument()
    expect(within(rows[index]).queryByRole('button')).not.toBeInTheDocument()
  })
  expect(title.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(within(screen.getByRole('main')).getByRole('img')).toHaveAttribute('src', expect.stringContaining('mogumi-reading'))
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '코스' })).toHaveFocus()
    expect(document.title).toBe('코스 · 머금')
  })
})

test.each([
  { shortened: 2, revisited: 1 },
  { shortened: 0, revisited: 3 },
  { shortened: 3, revisited: 0 },
])('검토 중 · 짧게 다룬 $shortened · 다시 짚은 $revisited 개념을 검토 영역의 한 줄로 보인다', async (masteryReport) => {
  open({ ...review, masteryReport })
  await screen.findByRole('heading', { name: review.title })
  await waitFor(() => {
    const region = screen.getByRole('region', { name: '코스 검토' })
    expect(within(region).getByText(`이전 복습 기록을 바탕으로 ${masteryReport.shortened}개 개념을 짧게 다뤘고 ${masteryReport.revisited}개 개념을 다시 짚었어요.`)).toBeVisible()
    expect(screen.getByRole('main')).not.toHaveTextContent(/잘 앎|흔들림|기록 없음|%|핵심 복습 반영|메모 목표 연계|맞춤형 커리큘럼 반영 완료/)
  })
})

test.each([
  { name: '두 수가 모두 0', data: { ...review, masteryReport: { shortened: 0, revisited: 0 } } },
  { name: '내역 없음', data: review },
])('검토 중 · $name · 숙련도 반영 줄과 대체 안내를 두지 않는다', async ({ data }) => {
  open(data)
  await screen.findByRole('heading', { name: review.title })
  await waitFor(() => {
    const region = screen.getByRole('region', { name: '코스 검토' })
    expect(screen.queryByText(/이전 복습 기록을 바탕으로/)).not.toBeInTheDocument()
    expect(region).not.toHaveTextContent(/복습|숙련도|반영|0개/)
    expect(within(region).queryByRole('img')).not.toBeInTheDocument()
    expect(within(region).getByRole('button', { name: '이대로 시작' })).toBeEnabled()
  })
})

test.each([learning, done].flatMap((data) => [
  { ...data, masteryReport: undefined },
  { ...data, masteryReport: { shortened: 2, revisited: 1 } },
]))('$status · 숙련도 반영 내역 $masteryReport · 반영 줄을 보이지 않는다', async (data) => {
  open(data)
  await screen.findByRole('heading', { name: data.title })
  await waitFor(() => {
    expect(screen.queryByText(/이전 복습 기록을 바탕으로/)).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '코스 검토' })).not.toBeInTheDocument()
    expect(screen.getByRole('main')).not.toHaveTextContent(/잘 앎|흔들림|기록 없음|%/)
  })
})

test('이대로 시작은 응답으로 같은 코스 화면을 학습 중으로 바꾸고 1강을 열지 않는다', async () => {
  const started = { ...review, status: 'LEARNING', nextLessonId: 51 }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ ...review, masteryReport: { shortened: 2, revisited: 1 } })).mockResolvedValueOnce(response(started)))
  const { router, client } = open()
  await screen.findByRole('heading', { name: review.title })
  await waitFor(() => expect(screen.getByText('이전 복습 기록을 바탕으로 2개 개념을 짧게 다뤘고 1개 개념을 다시 짚었어요.')).toBeVisible())
  fireEvent.click(await screen.findByRole('button', { name: '이대로 시작' }))
  await screen.findByText('학습 중')
  await waitFor(() => {
    expect(screen.getByText('학습 중')).toBeVisible()
    expect(screen.getByText('0 / 2')).toBeVisible()
    expect(screen.getByRole('region', { name: '다음에 열 강의' })).toHaveTextContent('1강 · 변수')
    expect(router.state.location.pathname).toBe('/courses/31')
    expect(client.getQueryData(['courses', '31'])).toEqual(started)
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('button', { name: '이대로 시작' })).not.toBeInTheDocument()
    expect(screen.queryByText(/이전 복습 기록을 바탕으로/)).not.toBeInTheDocument()
  })
})

test('끝나지 않은 다시 짜기로 시작이 거절되면 서버 문구와 검토 중을 유지한다', async () => {
  const message = '다시 짜는 중에는 시작할 수 없어요'
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(failure('REPLAN_IN_PROGRESS', message)))
  const { router } = open()
  fireEvent.click(await screen.findByRole('button', { name: '이대로 시작' }))
  expect(await screen.findByText(message)).toBeVisible()
  expect(screen.getByText('시작하지 못했어요.')).toBeVisible()
  expect(screen.getByText('검토 중')).toBeVisible()
  expect(router.state.location.pathname).toBe('/courses/31')
  expect(screen.queryByText('REPLAN_IN_PROGRESS')).not.toBeInTheDocument()
})

test.each(['start', 'replan', 'title'])('%s 요청 중 모든 동작을 잠그고 중복 제출하지 않는다', async (action) => {
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockImplementation(() => new Promise<Response>((resolve) => { finish = resolve })))
  open()
  await screen.findByRole('heading', { name: review.title })
  if (action === 'replan') await replan()
  if (action === 'title') fireEvent.click(screen.getByRole('button', { name: '코스 제목 고치기' }))
  const submitName = action === 'start' ? '이대로 시작' : action === 'replan' ? '다시 짜기 요청' : '제목 저장'
  fireEvent.click(screen.getByRole('button', { name: submitName }))
  await waitFor(() => {
    for (const button of within(screen.getByRole('main')).getAllByRole('button')) expect(button).toBeDisabled()
    for (const input of screen.queryAllByRole('textbox')) expect(input).toBeDisabled()
  })
  fireEvent.click(screen.getByRole('button', { name: submitName }))
  expect(fetch).toHaveBeenCalledTimes(2)
  await act(async () => { finish(action === 'replan' ? response(job) : response(review)) })
})

test.each([false, true])('다시 짜기는 피드백과 대상만 보내고 받은 작업으로 간다 (기존 작업 반환: %s)', async (existing) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(response({ ...job, status: existing ? 'FAILED' : 'QUEUED' })))
  const { router } = open()
  const input = await replan()
  await waitFor(() => expect(input).toHaveFocus())
  fireEvent.change(input, { target: { value: '예제를 더해 주세요' } })
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  expect(await screen.findByRole('heading', { name: '기다리는 화면 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe('/jobs/77')
  expect(fetch).toHaveBeenLastCalledWith('/api/jobs', expect.objectContaining({ method: 'POST', body: JSON.stringify({ type: 'REPLAN_COURSE', courseId: 31, feedback: '예제를 더해 주세요' }) }))
})

test('피드백은 선택이며 비었으면 보내지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(response(job)))
  open()
  await replan()
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(fetch).toHaveBeenLastCalledWith('/api/jobs', expect.objectContaining({ body: JSON.stringify({ type: 'REPLAN_COURSE', courseId: 31 }) }))
})

test('다시 짜기 펼침 버튼은 열린 입력만 aria-controls로 가리킨다', async () => {
  open()
  const button = await screen.findByRole('button', { name: '다시 짜기' })
  expect(button).toHaveAttribute('aria-expanded', 'false')
  expect(button).not.toHaveAttribute('aria-controls')

  for (const closeWith of ['다시 짜기', '다시 짜기 취소']) {
    fireEvent.click(button)
    const input = screen.getByRole('textbox', { name: '고칠 점' })
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(button).toHaveAttribute('aria-controls', 'course-replan')
    expect(document.getElementById(button.getAttribute('aria-controls')!)).toContainElement(input)
    await waitFor(() => expect(input).toHaveFocus())

    fireEvent.click(screen.getByRole('button', { name: closeWith }))
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).not.toHaveAttribute('aria-controls')
    expect(screen.queryByRole('textbox', { name: '고칠 점' })).not.toBeInTheDocument()
    if (closeWith === '다시 짜기 취소') await waitFor(() => expect(button).toHaveFocus())
  }
})

test('피드백은 코드 포인트 500자까지 보내며 초과하면 막는다', async () => {
  open()
  const input = await replan()
  fireEvent.change(input, { target: { value: '😀'.repeat(500) } })
  expect(screen.getByRole('button', { name: '다시 짜기 요청' })).toBeEnabled()
  fireEvent.change(input, { target: { value: '😀'.repeat(501) } })
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByRole('button', { name: '다시 짜기 요청' })).toBeDisabled()
})

test.each([
  ['CREDIT_INSUFFICIENT', '크레딧이 부족합니다. 2026-12-01에 다시 채워집니다'],
  ['TOO_MANY_IN_PROGRESS', '지금 만드는 것이 끝나면 다시 시도해 주세요'],
])('다시 짜기 거절 %s는 공통 안내·피드백을 보존한다', async (code, message) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(failure(code, message)))
  const { router } = open()
  const input = await replan()
  fireEvent.change(input, { target: { value: '기초를 더해 주세요' } })
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  await screen.findByText('지금은 만들 수 없어요')
  await waitFor(() => {
    const notice = screen.getByRole('status')
    expect(notice).toHaveTextContent(message)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/안내를 확인한 뒤 다시 짜기 요청을 다시/)).not.toBeInTheDocument()
    expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
    const link = within(notice).queryByRole('link', { name: '남은 크레딧 보기' })
    if (code === 'CREDIT_INSUFFICIENT') expect(link).toHaveAttribute('href', '/profile')
    else expect(link).not.toBeInTheDocument()
    expect(input).toHaveValue('기초를 더해 주세요')
    expect(screen.getByRole('button', { name: '다시 짜기 요청' })).toBeEnabled()
    expect(screen.queryByText(code)).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/courses/31')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})

test.each(['REPLAN_NOT_ALLOWED', 'network'])('다시 짜기 실패 %s는 기존 실패·재제출 안내를 유지한다', async (kind) => {
  const fetcher = vi.fn().mockResolvedValueOnce(response(review))
  if (kind === 'network') fetcher.mockRejectedValueOnce(new Error('secret-network-detail'))
  else fetcher.mockResolvedValueOnce(failure(kind, '검토 중일 때만 다시 짤 수 있어요'))
  vi.stubGlobal('fetch', fetcher)
  open()
  await replan()
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  await screen.findByRole('alert')
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('다시 짜기를 요청하지 못했어요.')
    expect(screen.getByRole('alert')).toHaveTextContent(kind === 'network' ? '연결하지 못했어요. 다시 시도해 주세요.' : '검토 중일 때만 다시 짤 수 있어요')
    expect(screen.getByText('안내를 확인한 뒤 다시 짜기 요청을 다시 눌러 주세요.')).toBeVisible()
    expect(screen.queryByText('지금은 만들 수 없어요')).not.toBeInTheDocument()
    expect(screen.queryByText('secret-network-detail')).not.toBeInTheDocument()
  })
})

test('피드백 칸 오류는 해당 칸과 연결된다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(failure('FIELD_INVALID', '입력을 확인해 주십시오', [{ field: 'feedback', reason: 'TOO_LONG' }])))
  open()
  const input = await replan()
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  await waitFor(() => expect(input).toHaveAccessibleDescription(/글자 수 상한을 넘었어요/))
})

test('학습 중은 진도·다음 강의를 강조하고 완료한 강의와 다음 강의만 연다', async () => {
  const locked = { ...lessons[1], id: 53, position: 3, title: '배열' }
  const { router } = open({ ...learning, lessons: [...learning.lessons, locked] })
  await screen.findByRole('heading', { name: review.title })
  expect(screen.getByText('학습 중')).toBeVisible()
  expect(screen.getByRole('progressbar', { name: '학습 진도' })).toHaveAttribute('aria-valuenow', '1')
  expect(screen.getByText('1 / 2')).toBeVisible()
  const next = screen.getByRole('region', { name: '다음에 열 강의' })
  expect(next).toHaveTextContent('2강 · 반복문')
  expect(next).toHaveClass('bg-primary', 'text-on-primary')
  const list = screen.getByRole('list', { name: '강의 목록' })
  expect(within(list).getByText('학습 완료')).toBeVisible()
  for (const row of within(list).getAllByRole('listitem')) fireEvent.click(row)
  expect(router.state.location.pathname).toBe('/courses/31')
  expect(within(list).getAllByRole('link')).toHaveLength(2)
  expect(within(list).getByRole('link', { name: '1강 · 변수' })).toHaveAttribute('href', '/courses/31/lessons/51')
  expect(within(list).getByRole('link', { name: '2강 · 반복문' })).toHaveAttribute('href', '/courses/31/lessons/52')
  const lockedRow = within(list).getAllByRole('listitem')[2]
  expect(within(lockedRow).queryByRole('link')).not.toBeInTheDocument()
  expect(lockedRow).toHaveTextContent('앞 강의를 학습 완료하면 열 수 있어요.')
  expect(within(list).queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다시 짜기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '이대로 시작' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '로드맵 화면으로 가기' })).not.toBeInTheDocument()
  fireEvent.click(within(next).getByRole('link', { name: '2강 · 반복문' }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/courses/31/lessons/52'))
})

test.each([undefined, 41])('다 마침은 한국 날짜·강의 목록을 보이고 로드맵 %s일 때만 돌아갈 길을 둔다', async (roadmapId) => {
  open({ ...done, roadmapId })
  await screen.findByRole('heading', { name: review.title })
  expect(screen.getByText('다 마침')).toBeVisible()
  expect(screen.getByText('이 코스의 모든 강의를 다 마쳤어요.')).toBeVisible()
  expect(screen.getByText('2026. 11. 18.')).toHaveAttribute('dateTime', done.completedAt)
  const list = screen.getByRole('list', { name: '강의 목록' })
  expect(list).toBeVisible()
  expect(within(list).getAllByRole('link')).toHaveLength(2)
  expect(screen.getByRole('button', { name: '코스 제목 고치기' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '다시 짜기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '이대로 시작' })).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '다음에 열 강의' })).not.toBeInTheDocument()
  const link = screen.queryByRole('link', { name: '로드맵 화면으로 가기' })
  if (roadmapId) expect(link).toHaveAttribute('href', '/roadmaps/41')
  else expect(link).not.toBeInTheDocument()
})

test.each([learning, done].flatMap((data) => [1, 3].map((todayReviewCount) => ({ ...data, todayReviewCount }))))(
  '$status · 오늘 복습할 카드 $todayReviewCount 장이면 강의 목록 아래에 코스 복습 링크를 보인다', async (data) => {
    open(data)
    const link = await screen.findByRole('link', { name: `이 코스 복습하기 · ${data.todayReviewCount}장` })
    expect(link).toBeVisible()
    expect(link).toHaveAttribute('href', '/review?courseId=31')
    expect(link).not.toHaveAttribute('target')
    const list = screen.getByRole('list', { name: '강의 목록' })
    expect(list.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    if (data.status === 'LEARNING') {
      const next = screen.getByRole('region', { name: '다음에 열 강의' })
      expect(next).toHaveClass('bg-primary', 'text-on-primary')
      expect(next.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledWith('/api/courses/31', expect.objectContaining({ method: 'GET' }))
  },
)

test.each([learning, done])('$status · 오늘 복습할 카드가 없으면 복습 링크와 안내를 두지 않는다', async (data) => {
  open({ ...data, todayReviewCount: 0 })
  await screen.findByRole('heading', { name: data.title })
  expect(screen.getByRole('main')).not.toHaveTextContent(/복습|0장/)
})

test.each(['REVIEWING', 'NOT_PLANNED'].flatMap((status) => [0, 3].map((todayReviewCount) => ({ status, todayReviewCount }))))(
  '$status · 오늘 복습할 카드 $todayReviewCount 장이어도 복습 입구를 두지 않는다', async ({ status, todayReviewCount }) => {
    open({ ...review, status, todayReviewCount, roadmapId: 41 })
    if (status === 'NOT_PLANNED') await screen.findByRole('link', { name: '로드맵 화면으로 가기' })
    else await screen.findByRole('heading', { name: review.title })
    expect(screen.getByRole('main')).not.toHaveTextContent(/복습|장/)
  },
)

test('다 마친 로드맵 코스의 링크가 로드맵으로 이동한다', async () => {
  const { router } = open(done)
  fireEvent.click(await screen.findByRole('link', { name: '로드맵 화면으로 가기' }))
  expect(await screen.findByRole('heading', { name: '로드맵 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe('/roadmaps/41')
})

test.each([review, learning, done])('$status 제목 편집 중에는 다시 열기를 막아 입력을 보존하고 취소 뒤 다시 연다', async (data) => {
  open(data)
  const button = await screen.findByRole('button', { name: '코스 제목 고치기' })
  fireEvent.click(button)
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  await waitFor(() => expect(input).toHaveFocus())
  fireEvent.change(input, { target: { value: '입력 중인 이름' } })

  expect(button).toBeDisabled()
  fireEvent.click(button)
  expect(input).toHaveValue('입력 중인 이름')

  fireEvent.click(screen.getByRole('button', { name: '제목 수정 취소' }))
  expect(button).toBeEnabled()
  expect(screen.queryByRole('textbox', { name: '코스 제목' })).not.toBeInTheDocument()
  await waitFor(() => expect(button).toHaveFocus())

  fireEvent.click(button)
  const reopened = screen.getByRole('textbox', { name: '코스 제목' })
  expect(reopened).toHaveValue(data.title)
  await waitFor(() => expect(reopened).toHaveFocus())
  expect(fetch).toHaveBeenCalledTimes(1)
})

test.each([review, learning, done])('$status 제목 저장은 응답을 표시하고 같은 화면에 남는다', async (data) => {
  const updated = { ...data, title: '바꾼 이름' }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(data)).mockResolvedValue(response(updated)))
  const { client, router } = open()
  fireEvent.click(await screen.findByRole('button', { name: '코스 제목 고치기' }))
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  expect(input).toHaveValue(review.title)
  await waitFor(() => expect(input).toHaveFocus())
  fireEvent.change(input, { target: { value: '바꾼 이름' } })
  fireEvent.click(screen.getByRole('button', { name: '제목 저장' }))
  expect(await screen.findByRole('heading', { name: '바꾼 이름' })).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button', { name: '코스 제목 고치기' })).toHaveFocus())
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(client.getQueryData(['courses', '31'])).toEqual(updated)
  expect(router.state.location.pathname).toBe('/courses/31')
})

test.each(['REQUIRED', 'TOO_LONG'])('제목의 %s 서버 칸 오류는 칸에 표시하고 입력을 보존한다', async (reason) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(failure('FIELD_INVALID', '입력을 확인해 주십시오', [{ field: 'title', reason }])))
  open()
  fireEvent.click(await screen.findByRole('button', { name: '코스 제목 고치기' }))
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  fireEvent.change(input, { target: { value: '고친 이름' } })
  fireEvent.click(screen.getByRole('button', { name: '제목 저장' }))
  expect(await screen.findByText('입력을 확인해 주십시오')).toBeVisible()
  expect(input).toHaveValue('고친 이름')
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(input).toHaveAccessibleDescription(new RegExp(reason === 'REQUIRED' ? '필수 입력이에요' : '글자 수 상한을 넘었어요'))
  expect(screen.getByRole('button', { name: '제목 저장' })).toBeEnabled()
})

test('제목은 공백을 막고 코드 포인트 100자까지 허용하며 취소는 저장하지 않는다', async () => {
  open()
  fireEvent.click(await screen.findByRole('button', { name: '코스 제목 고치기' }))
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  for (const value of ['', '  ', '😀'.repeat(101)]) {
    fireEvent.change(input, { target: { value } })
    expect(screen.getByRole('button', { name: '제목 저장' })).toBeDisabled()
  }
  fireEvent.change(input, { target: { value: '😀'.repeat(100) } })
  expect(screen.getByRole('button', { name: '제목 저장' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '제목 수정 취소' }))
  expect(screen.getByRole('heading', { name: review.title })).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button', { name: '코스 제목 고치기' })).toHaveFocus())
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('짜기 전 코스는 목록·제목 편집 없이 로드맵으로 가는 길만 보인다', async () => {
  open({ ...review, status: 'NOT_PLANNED', roadmapId: 41, lessons: [] })
  expect(await screen.findByRole('link', { name: '로드맵 화면으로 가기' })).toHaveAttribute('href', '/roadmaps/41')
  expect(screen.queryByRole('heading', { name: review.title })).not.toBeInTheDocument()
  expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByRole('list', { name: '강의 목록' })).not.toBeInTheDocument()
})

test.each(['NOT_FOUND', 'network', 'FUTURE_STATUS'])('%s 읽기 실패는 안내·다시 시도·홈으로를 보이고 재시도할 수 있다', async (kind) => {
  const fetcher = vi.fn()
  if (kind === 'network') fetcher.mockRejectedValueOnce(new Error('secret-technical'))
  else fetcher.mockResolvedValueOnce(kind === 'NOT_FOUND' ? failure(kind, '찾을 수 없습니다') : response({ ...review, status: kind }))
  fetcher.mockResolvedValue(response(review))
  vi.stubGlobal('fetch', fetcher)
  open()
  expect(await screen.findByRole('alert')).toHaveTextContent('코스를 불러오지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent(kind === 'NOT_FOUND' ? '찾을 수 없습니다' : kind === 'network' ? '연결하지 못했어요. 다시 시도해 주세요.' : '다시 불러와 주세요.')
  expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/')
  expect(screen.getByRole('main')).not.toHaveTextContent(/secret-|FUTURE_STATUS|NOT_FOUND/)
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('heading', { name: review.title })).toBeVisible()
})

test('동작 연결 실패는 기술 상세를 숨기고 재시도할 수 있게 한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockRejectedValueOnce(new Error('secret-technical')).mockResolvedValue(response({ ...review, status: 'LEARNING', nextLessonId: 51 })))
  open()
  fireEvent.click(await screen.findByRole('button', { name: '이대로 시작' }))
  expect(await screen.findByText('연결하지 못했어요. 다시 시도해 주세요.')).toBeVisible()
  expect(screen.getByRole('main')).not.toHaveTextContent('secret-technical')
  fireEvent.click(screen.getByRole('button', { name: '이대로 시작' }))
  expect(await screen.findByText('학습 중')).toBeVisible()
})

test.each([review, learning, done])('$status에서 미구현 동작·시스템 정보·시안 전용 정보를 보이지 않는다', async (data) => {
  open({ ...data, model: 'secret-model', credits: 999, masteryReport: { shortened: 2, revisited: 1 }, memo: 'secret-memo' })
  await screen.findByRole('heading', { name: review.title })
  await waitFor(() => {
    expect(screen.getByRole('main')).not.toHaveTextContent(/secret-|999|토큰|비용|모델|크레딧|수료|배지|퀴즈|예상|소요|핵심 복습 반영|메모|복습하기|처음부터|순서 바꾸기|첫 강의가 열|잘 앎|흔들림|기록 없음|%/)
  })
})

const learnedDeleteMessage = '강의는 사라지고 카드는 복습에 나오지 않아요. 그동안의 카드와 복습 기록은 다음 강의를 내 수준에 맞추는 데 계속 쓰여요.'
test.each(['REVIEWING', 'LEARNING', 'COMPLETED'])('단일 코스 %s는 목록·복습 뒤에 지우기를 두고 계약 문구로 확인한다', async (status) => {
  open({ ...review, status, todayReviewCount: 2 })
  const trigger = await screen.findByRole('button', { name: '코스 지우기' })
  expect(screen.getByRole('list', { name: '강의 목록' }).compareDocumentPosition(trigger) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  if (status !== 'REVIEWING') expect(screen.getByRole('link', { name: '이 코스 복습하기 · 2장' }).compareDocumentPosition(trigger) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  fireEvent.click(trigger)
  await waitFor(() => {
    const region = screen.getByRole('region', { name: '코스를 지울까요?' })
    expect(region).toHaveAccessibleDescription(status === 'REVIEWING' ? '이 코스의 강의 목록이 사라져요. 되돌릴 수 없어요.' : learnedDeleteMessage)
    expect(screen.getByRole('button', { name: '취소' })).toHaveFocus()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
  })
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  await waitFor(() => {
    expect(trigger).toHaveFocus()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('button', { name: '지우기' })).not.toBeInTheDocument()
  })
  expect(fetch).toHaveBeenCalledTimes(1)
})

test.each(['NOT_PLANNED', 'REVIEWING', 'LEARNING', 'COMPLETED'])('로드맵의 %s 코스는 지우기를 두지 않는다', async (status) => {
  open({ ...review, status, roadmapId: 41 })
  if (status === 'NOT_PLANNED') await screen.findByRole('link', { name: '로드맵 화면으로 가기' })
  else await screen.findByRole('heading', { name: review.title })
  expect(screen.queryByRole('button', { name: '코스 지우기' })).not.toBeInTheDocument()
})

test('단일 코스라도 짜기 전에는 지우기를 두지 않는다', async () => {
  open({ ...review, status: 'NOT_PLANNED' })
  await screen.findByRole('link', { name: '홈으로' })
  expect(screen.queryByRole('button', { name: '코스 지우기' })).not.toBeInTheDocument()
})

test('지우기 연타는 DELETE 한 번이며 요청 중 모든 동작을 잠그고 성공 뒤 캐시 정리·replace 이동한다', async () => {
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockImplementation(() => new Promise<Response>((resolve) => { finish = resolve })))
  const { client, router } = open()
  await screen.findByRole('heading', { name: review.title })
  client.setQueryData(['courses'], { courses: [review] })
  client.setQueryData(['courses', '32'], { ...review, id: 32 })
  client.setQueryData(['home', 'todos'], { items: [] })
  fireEvent.click(screen.getByRole('button', { name: '코스 제목 고치기' }))
  await replan()
  fireEvent.click(screen.getByRole('button', { name: '코스 지우기' }))
  const confirm = screen.getByRole('button', { name: '지우기' })
  act(() => { fireEvent.click(confirm); fireEvent.click(confirm) })
  await waitFor(() => {
    for (const button of within(screen.getByRole('main')).getAllByRole('button')) expect(button).toBeDisabled()
    for (const input of screen.getAllByRole('textbox')) expect(input).toBeDisabled()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  expect(fetch).toHaveBeenLastCalledWith('/api/courses/31', expect.objectContaining({ method: 'DELETE' }))
  expect(vi.mocked(fetch).mock.calls.at(-1)?.[1]?.body).toBeUndefined()
  await act(async () => { finish(new Response(null, { status: 204 })) })
  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/courses')
    expect(router.state.historyAction).toBe('REPLACE')
    expect(client.getQueryState(['courses', '31'])).toBeUndefined()
    expect(client.getQueryState(['courses'])?.isInvalidated).toBe(true)
    expect(client.getQueryState(['home', 'todos'])?.isInvalidated).toBe(true)
    expect(client.getQueryData(['courses', '32'])).toBeDefined()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  await act(async () => { await router.navigate(-1) })
  expect(router.state.location.pathname).toBe('/')
})

test.each(['server', 'network'])('지우기 %s 실패는 문구·확인 펼침·캐시를 보존하고 다시 누를 수 있다', async (kind) => {
  const fetcher = vi.fn().mockResolvedValueOnce(response(review))
  if (kind === 'server') fetcher.mockResolvedValueOnce(failure('COURSE_DELETE_NOT_ALLOWED', '로드맵의 코스는 로드맵을 지워야 해요'))
  else fetcher.mockRejectedValueOnce(new Error('secret-network'))
  vi.stubGlobal('fetch', fetcher.mockResolvedValue(new Response(null, { status: 204 })))
  const { client, router } = open()
  fireEvent.click(await screen.findByRole('button', { name: '코스 지우기' }))
  fireEvent.click(screen.getByRole('button', { name: '지우기' }))
  await screen.findByRole('alert')
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent(kind === 'server' ? '로드맵의 코스는 로드맵을 지워야 해요' : '연결하지 못했어요. 다시 시도해 주세요.')
    expect(screen.getByRole('region', { name: '코스를 지울까요?' })).toBeVisible()
    expect(screen.getByRole('button', { name: '지우기' })).toBeEnabled()
    expect(client.getQueryData(['courses', '31'])).toEqual(review)
    expect(router.state.location.pathname).toBe('/courses/31')
    expect(screen.getByRole('main')).not.toHaveTextContent(/COURSE_DELETE_NOT_ALLOWED|secret-network/)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  fireEvent.click(screen.getByRole('button', { name: '지우기' }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/courses'))
  expect(fetch).toHaveBeenCalledTimes(3)
})
