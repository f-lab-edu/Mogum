import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import RoadmapPage from './RoadmapPage'

const courses = [
  { id: 31, position: 1, title: '자바 기초', description: '기본 문법을 배워요', concepts: [{ name: '변수', definition: '값에 붙인 이름' }], status: 'NOT_PLANNED', progress: 'NOT_PLANNED' },
  { id: 32, position: 2, title: '객체 지향', description: '객체로 나눠요', concepts: [{ name: '객체', definition: '데이터와 동작을 묶은 것' }], status: 'NOT_PLANNED', progress: 'NOT_PLANNED' },
]
const review = { id: 41, title: '자바 로드맵', description: '기초부터 실무까지 배워요', status: 'REVIEWING', courses }
const running = { ...review, status: 'IN_PROGRESS', courses: [{ ...courses[0], status: 'LEARNING', progress: 'GENERATED' }, courses[1]], nextCourse: { courseId: 32, enabled: false } }
const job = { id: 77, type: 'PLAN_ROADMAP_COURSE', status: 'QUEUED', subject: { title: courses[0].title } }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function failure(code: string, message: string, fields?: unknown) { return new Response(JSON.stringify({ code, message, fields }), { status: 409 }) }
function open(data: unknown = review) {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/', element: <h1>홈 자리</h1> },
      { path: '/roadmaps/:roadmapId', element: <AppLayout title="로드맵"><RoadmapPage /></AppLayout> },
      { path: '/courses/:courseId', element: <h1>코스 자리</h1> },
      { path: '/jobs/:jobId', element: <h1>기다리는 화면 자리</h1> },
    ],
  }], { initialEntries: ['/', '/roadmaps/41'] })
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

test('검토 중은 제목·설명 다음에 순서 있는 계획과 코스별 개념을 보인다', async () => {
  open()
  const title = await screen.findByRole('heading', { name: review.title })
  expect(screen.getByText(review.description)).toBeVisible()
  expect(screen.getByText('검토 중')).toBeVisible()
  const list = screen.getByRole('list', { name: '로드맵 계획' })
  const rows = within(list).getAllByRole('listitem')
  expect(rows).toHaveLength(2)
  courses.forEach((course, i) => {
    expect(rows[i]).toHaveTextContent(`${course.position}코스 · ${course.title}`)
    expect(rows[i]).toHaveTextContent(course.description)
    expect(rows[i]).toHaveTextContent(course.concepts[0].name)
    expect(rows[i]).toHaveTextContent(course.concepts[0].definition)
    expect(within(rows[i]).queryByRole('link')).not.toBeInTheDocument()
    expect(within(rows[i]).getByRole('button', { name: '코스 제목 고치기' })).toBeEnabled()
  })
  expect(title.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다음 코스 만들기' })).not.toBeInTheDocument()
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '로드맵' })).toHaveFocus()
    expect(document.title).toBe('로드맵 · 머금')
  })
})

test('이대로 시작은 순서 1의 코스 받기만 보내고 받은 작업으로 간다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(response(job)))
  const { router } = open()
  fireEvent.click(await screen.findByRole('button', { name: '이대로 시작' }))
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(router.state.location.pathname).toBe('/jobs/77')
  expect(fetch).toHaveBeenLastCalledWith('/api/jobs', expect.objectContaining({ method: 'POST', body: JSON.stringify({ type: 'PLAN_ROADMAP_COURSE', courseId: 31 }) }))
})

test.each(['시작', '다시 짜기', '다음 코스'].flatMap((action) => [
  { action, code: 'CREDIT_INSUFFICIENT', message: '크레딧이 부족합니다. 2026-12-01에 다시 채워집니다' },
  { action, code: 'TOO_MANY_IN_PROGRESS', message: '지금 만드는 것이 끝나면 다시 시도해 주세요' },
]))('$action 거절 $code는 공통 안내·서버 문구를 보이고 화면에 남는다', async ({ action, code, message }) => {
  const data = action === '다음 코스' ? { ...running, nextCourse: { courseId: 32, enabled: true } } : review
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(data)).mockResolvedValue(failure(code, message)))
  const { router } = open()
  if (action === '다시 짜기') {
    const input = await replan()
    fireEvent.change(input, { target: { value: '기초를 더해 주세요' } })
  }
  const buttonName = action === '시작' ? '이대로 시작' : action === '다시 짜기' ? '다시 짜기 요청' : '다음 코스 만들기'
  fireEvent.click(await screen.findByRole('button', { name: buttonName }))
  await screen.findByText('지금은 만들 수 없어요')
  await waitFor(() => {
    const notice = screen.getByRole('status')
    expect(notice).toHaveTextContent(message)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/안내를 확인한 뒤 같은 버튼을 다시/)).not.toBeInTheDocument()
    expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
    const link = within(notice).queryByRole('link', { name: '남은 크레딧 보기' })
    if (code === 'CREDIT_INSUFFICIENT') expect(link).toHaveAttribute('href', '/profile')
    else expect(link).not.toBeInTheDocument()
    if (action === '다시 짜기') expect(screen.getByRole('textbox', { name: '고칠 점' })).toHaveValue('기초를 더해 주세요')
    expect(screen.getByText(action === '다음 코스' ? '진행 중' : '검토 중')).toBeVisible()
    expect(screen.getByRole('button', { name: buttonName })).toBeEnabled()
    expect(router.state.location.pathname).toBe('/roadmaps/41')
    expect(screen.getByRole('main')).not.toHaveTextContent(code)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})

test.each(['시작', '다시 짜기', '다음 코스'].flatMap((action) => [
  { action, code: action === '다시 짜기' ? 'REPLAN_NOT_ALLOWED' : 'REPLAN_IN_PROGRESS' },
  { action, code: 'network' },
]))('$action 실패 $code는 기존 실패·재제출 안내를 유지한다', async ({ action, code }) => {
  const data = action === '다음 코스' ? { ...running, nextCourse: { courseId: 32, enabled: true } } : review
  const message = code === 'REPLAN_NOT_ALLOWED' ? '검토 중일 때만 다시 짤 수 있어요' : '다시 짜는 중에는 시작할 수 없어요'
  const fetcher = vi.fn().mockResolvedValueOnce(response(data))
  if (code === 'network') fetcher.mockRejectedValueOnce(new Error('secret-network-detail'))
  else fetcher.mockResolvedValueOnce(failure(code, message))
  vi.stubGlobal('fetch', fetcher)
  const { router } = open()
  if (action === '다시 짜기') await replan()
  const buttonName = action === '시작' ? '이대로 시작' : action === '다시 짜기' ? '다시 짜기 요청' : '다음 코스 만들기'
  fireEvent.click(await screen.findByRole('button', { name: buttonName }))
  await screen.findByRole('alert')
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent(action === '다시 짜기' ? '다시 짜기를 요청하지 못했어요.' : '코스 만들기를 요청하지 못했어요.')
    expect(screen.getByRole('alert')).toHaveTextContent(code === 'network' ? '연결하지 못했어요. 다시 시도해 주세요.' : message)
    expect(screen.getByText('안내를 확인한 뒤 같은 버튼을 다시 눌러 주세요.')).toBeVisible()
    expect(screen.queryByText('지금은 만들 수 없어요')).not.toBeInTheDocument()
    expect(screen.queryByText('secret-network-detail')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: buttonName })).toBeEnabled()
    expect(router.state.location.pathname).toBe('/roadmaps/41')
  })
})

test.each(['', '예제를 더해 주세요'])('로드맵 다시 짜기는 대상·선택 피드백 %s만 보내고 작업으로 간다', async (feedback) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(response({ ...job, type: 'REPLAN_ROADMAP' })))
  const { router } = open()
  const input = await replan()
  await waitFor(() => expect(input).toHaveFocus())
  fireEvent.change(input, { target: { value: feedback } })
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(router.state.location.pathname).toBe('/jobs/77')
  expect(fetch).toHaveBeenLastCalledWith('/api/jobs', expect.objectContaining({ body: JSON.stringify({ type: 'REPLAN_ROADMAP', roadmapId: 41, ...(feedback ? { feedback } : {}) }) }))
})

test('피드백 500 코드 포인트·초과 잠금과 취소 포커스를 지킨다', async () => {
  open()
  const input = await replan()
  fireEvent.change(input, { target: { value: '😀'.repeat(500) } })
  expect(screen.getByRole('button', { name: '다시 짜기 요청' })).toBeEnabled()
  fireEvent.change(input, { target: { value: '😀'.repeat(501) } })
  expect(screen.getByRole('button', { name: '다시 짜기 요청' })).toBeDisabled()
  expect(input).toHaveAttribute('aria-invalid', 'true')
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 취소' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '다시 짜기' })).toHaveFocus())
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('다시 짜기 실패는 문구·피드백·칸 오류를 보존하고 재제출할 수 있다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(failure('FIELD_INVALID', '입력을 확인해 주십시오', [{ field: 'feedback', reason: 'TOO_LONG' }])))
  open()
  const input = await replan()
  fireEvent.change(input, { target: { value: '기초를 더해 주세요' } })
  fireEvent.click(screen.getByRole('button', { name: '다시 짜기 요청' }))
  expect(await screen.findByText('입력을 확인해 주십시오')).toBeVisible()
  expect(input).toHaveValue('기초를 더해 주세요')
  expect(input).toHaveAccessibleDescription(/글자 수 상한을 넘었어요/)
  expect(screen.getByRole('button', { name: '다시 짜기 요청' })).toBeEnabled()
})

test('계획 코스 제목만 PATCH로 고치고 계획·코스 캐시를 함께 맞춘다', async () => {
  const updated = { id: 32, status: 'NOT_PLANNED', title: '객체로 설계하기', roadmapId: 41, lessons: [], progress: { completedLessons: 0, totalLessons: 0 } }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(response(updated)))
  const { client, router } = open()
  await screen.findByRole('heading', { name: review.title })
  const row = within(screen.getByRole('list', { name: '로드맵 계획' })).getAllByRole('listitem')[1]
  fireEvent.click(within(row).getByRole('button', { name: '코스 제목 고치기' }))
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  await waitFor(() => expect(input).toHaveFocus())
  expect(input).toHaveValue(courses[1].title)
  fireEvent.change(input, { target: { value: updated.title } })
  fireEvent.click(screen.getByRole('button', { name: '제목 저장' }))
  expect(await screen.findByRole('heading', { name: `2코스 · ${updated.title}` })).toBeVisible()
  await waitFor(() => expect(within(row).getByRole('button', { name: '코스 제목 고치기' })).toHaveFocus())
  expect(client.getQueryData(['courses', '32'])).toEqual(updated)
  expect(client.getQueryData(['roadmaps', '41'])).toEqual({ ...review, courses: [courses[0], { ...courses[1], title: updated.title }] })
  expect(fetch).toHaveBeenLastCalledWith('/api/courses/32', expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ title: updated.title }) }))
  expect(router.state.location.pathname).toBe('/roadmaps/41')
})

test('계획 제목은 공백·100 코드 포인트 초과를 막고 취소하면 저장하지 않는다', async () => {
  open()
  fireEvent.click((await screen.findAllByRole('button', { name: '코스 제목 고치기' }))[0])
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  for (const value of ['', '  ', '😀'.repeat(101)]) {
    fireEvent.change(input, { target: { value } })
    expect(screen.getByRole('button', { name: '제목 저장' })).toBeDisabled()
  }
  fireEvent.change(input, { target: { value: '😀'.repeat(100) } })
  expect(screen.getByRole('button', { name: '제목 저장' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '제목 수정 취소' }))
  await waitFor(() => expect(screen.getAllByRole('button', { name: '코스 제목 고치기' })[0]).toHaveFocus())
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('제목 저장 실패는 입력·서버 문구·칸 오류를 보존한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(review)).mockResolvedValue(failure('FIELD_INVALID', '입력을 확인해 주십시오', [{ field: 'title', reason: 'REQUIRED' }])))
  open()
  fireEvent.click((await screen.findAllByRole('button', { name: '코스 제목 고치기' }))[0])
  const input = screen.getByRole('textbox', { name: '코스 제목' })
  fireEvent.change(input, { target: { value: '바꿀 이름' } })
  fireEvent.click(screen.getByRole('button', { name: '제목 저장' }))
  expect(await screen.findByText('입력을 확인해 주십시오')).toBeVisible()
  expect(input).toHaveValue('바꿀 이름')
  expect(input).toHaveAccessibleDescription(/필수 입력이에요/)
  expect(screen.getByRole('button', { name: '제목 저장' })).toBeEnabled()
})

test.each(['start', 'replan', 'title', 'next'])('%s 보내기 중 입력·동작을 잠그고 연타·동시 제출은 한 번만 보낸다', async (type) => {
  const data = type === 'next' ? { ...running, nextCourse: { courseId: 32, enabled: true } } : review
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(data)).mockImplementation(() => new Promise<Response>((resolve) => { finish = resolve })))
  open()
  await screen.findByRole('heading', { name: review.title })
  if (type === 'replan') await replan()
  if (type === 'title') fireEvent.click(screen.getAllByRole('button', { name: '코스 제목 고치기' })[0])
  const name = type === 'start' ? '이대로 시작' : type === 'replan' ? '다시 짜기 요청' : type === 'title' ? '제목 저장' : '다음 코스 만들기'
  const button = screen.getByRole('button', { name })
  act(() => { fireEvent.click(button); fireEvent.click(button) })
  await waitFor(() => {
    for (const button of within(screen.getByRole('main')).getAllByRole('button')) expect(button).toBeDisabled()
    for (const input of screen.queryAllByRole('textbox')) expect(input).toBeDisabled()
  })
  expect(fetch).toHaveBeenCalledTimes(2)
  await act(async () => { finish(type === 'title' ? response({ id: 31, roadmapId: 41, status: 'NOT_PLANNED', title: '기초', lessons: [], progress: { completedLessons: 0, totalLessons: 0 } }) : response(job)) })
})

test('진행 중은 서버의 네 진행 값·마친 코스와 계획을 보인다', async () => {
  const data = { ...running, courses: [
    { ...courses[0], status: 'COMPLETED', progress: 'GENERATED' },
    { ...courses[1], progress: 'GENERATING', jobId: 71 },
    { ...courses[1], id: 33, position: 3, title: '실전', progress: 'FAILED', jobId: 72 },
    { ...courses[1], id: 34, position: 4, title: '응용' },
  ] }
  open(data)
  await screen.findByRole('heading', { name: review.title })
  const list = screen.getByRole('list', { name: '로드맵 계획' })
  for (const text of ['만들어짐', '만드는 중', '실패', '짜기 전', '다 마침']) expect(within(list).getByText(text)).toBeVisible()
  expect(screen.getByRole('progressbar', { name: '마친 코스' })).toHaveAttribute('aria-valuenow', '1')
  expect(screen.getByText('1 / 4')).toBeVisible()
  expect(screen.queryByRole('button', { name: '다시 짜기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '이대로 시작' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '코스 제목 고치기' })).not.toBeInTheDocument()
  const rows = within(list).getAllByRole('listitem')
  expect(within(rows[3]).queryByRole('link')).not.toBeInTheDocument()
})

test.each([
  ['GENERATED', '/courses/31', '코스 자리'],
  ['GENERATING', '/jobs/71', '기다리는 화면 자리'],
  ['FAILED', '/jobs/71', '기다리는 화면 자리'],
])('%s 코스는 해당 코스 또는 서버 작업으로 이동한다', async (progress, path, heading) => {
  open({ ...running, nextCourse: undefined, courses: [{ ...courses[0], progress, status: progress === 'GENERATED' ? 'LEARNING' : 'NOT_PLANNED', jobId: progress === 'GENERATED' ? undefined : 71 }] })
  await screen.findByRole('heading', { name: review.title })
  const link = within(screen.getByRole('list', { name: '로드맵 계획' })).getByRole('link')
  expect(link).toHaveAttribute('href', path)
  fireEvent.click(link)
  expect(await screen.findByRole('heading', { name: heading })).toBeVisible()
})

test('다음 코스는 잠긴 버튼과 이유를 함께 보인다', async () => {
  open(running)
  const button = await screen.findByRole('button', { name: '다음 코스 만들기' })
  expect(button).toBeDisabled()
  expect(screen.getByText('앞 코스를 다 마치면 만들 수 있어요')).toBeVisible()
  expect(button).toHaveAccessibleDescription('앞 코스를 다 마치면 만들 수 있어요')
  fireEvent.click(button)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('다음 코스가 활성화되면 서버가 준 코스를 받아 기다리는 화면으로 간다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ ...running, nextCourse: { courseId: 32, enabled: true } })).mockResolvedValue(response(job)))
  const { router } = open()
  fireEvent.click(await screen.findByRole('button', { name: '다음 코스 만들기' }))
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(router.state.location.pathname).toBe('/jobs/77')
  expect(fetch).toHaveBeenLastCalledWith('/api/jobs', expect.objectContaining({ body: JSON.stringify({ type: 'PLAN_ROADMAP_COURSE', courseId: 32 }) }))
})

test.each(['IN_PROGRESS', 'COMPLETED'])('%s에서 서버가 다음 코스를 주지 않으면 버튼이 없다', async (status) => {
  open({ ...running, status, nextCourse: undefined })
  await screen.findByRole('heading', { name: review.title })
  expect(screen.queryByRole('button', { name: '다음 코스 만들기' })).not.toBeInTheDocument()
})

test('다 마침은 전체 완료와 계획을 보이고 다음 코스를 만들지 않는다', async () => {
  open({ ...review, status: 'COMPLETED', courses: courses.map((course) => ({ ...course, status: 'COMPLETED', progress: 'GENERATED' })), nextCourse: { courseId: 32, enabled: true } })
  expect(await screen.findByText('이 로드맵의 모든 코스를 다 마쳤어요.')).toBeVisible()
  expect(screen.getByText('2 / 2')).toBeVisible()
  expect(screen.getByRole('list', { name: '로드맵 계획' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '다음 코스 만들기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '다시 짜기' })).not.toBeInTheDocument()
})

test.each(['NOT_FOUND', 'network', 'FUTURE_STATUS'])('%s 읽기 실패는 안내·재시도·홈을 제공하고 내부 값을 숨긴다', async (kind) => {
  const fetcher = vi.fn()
  if (kind === 'network') fetcher.mockRejectedValueOnce(new Error('secret-error'))
  else fetcher.mockResolvedValueOnce(kind === 'NOT_FOUND' ? failure(kind, '찾을 수 없습니다') : response({ ...review, status: kind }))
  vi.stubGlobal('fetch', fetcher.mockResolvedValue(response(review)))
  open()
  expect(await screen.findByRole('alert')).toHaveTextContent('로드맵을 불러오지 못했어요.')
  expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/')
  expect(screen.getByRole('main')).not.toHaveTextContent(/secret-|NOT_FOUND|FUTURE_STATUS/)
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('heading', { name: review.title })).toBeVisible()
})

test.each([review, running, { ...running, status: 'COMPLETED' }])('$status에서 시안 전용·금지 정보와 기능은 없다', async (data) => {
  open({ ...data, model: 'secret-model', credits: 999, memo: 'secret-memo' })
  await screen.findByRole('heading', { name: review.title })
  expect(screen.getByRole('main')).not.toHaveTextContent(/secret-|999|토큰|비용|모델|수료증|퀴즈|광고|진도율|메모|지우기|저절로|ADR-/)
})
