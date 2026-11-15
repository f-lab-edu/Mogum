import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import WaitingPage from './WaitingPage'

const queued = { id: 12, type: 'NEW_COURSE', status: 'QUEUED', subject: { topic: '자바', level: 'BEGINNER', purpose: 'WORK' } }
const temporary = { ...queued, status: 'FAILED', error: { code: 'JOB_TEMPORARY_FAILURE', message: '서버가 내려준 일시적 실패 문구 그대로' } }
const inputFailure = { ...queued, status: 'FAILED', error: { code: 'JOB_INPUT_FAILURE', message: '주제를 좁혀 새로 만들어 주세요' } }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function open(data: unknown = queued) {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/', element: <h1>홈 자리</h1> },
      { path: '/jobs/:jobId', element: <AppLayout title="기다리는 화면"><WaitingPage /></AppLayout> },
      ...['/courses/:courseId', '/roadmaps/:roadmapId', '/courses/:courseId/lessons/:lessonId'].map((path) => ({ path, element: <h1>결과 자리</h1> })),
    ],
  }], { initialEntries: ['/', '/jobs/12'] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return { router, client }
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

test('모구미·입력·상태·떠나도 된다는 안내 순서와 제목 포커스를 제공한다', async () => {
  open()
  const main = screen.getByRole('main')
  const topic = await screen.findByRole('heading', { name: '자바' })
  const mogumi = within(main).getByRole('img')
  const status = screen.getByRole('status')
  const leave = screen.getByText('이 화면을 떠나도 계속 만들어요. 홈에서 다시 열 수 있어요.')
  expect(screen.getByText('처음 배워요')).toBeVisible()
  expect(screen.getByText('업무에 쓰려고요')).toBeVisible()
  expect(status).toHaveTextContent('차례를 기다리고 있어요')
  for (const [before, after] of [[mogumi, topic], [topic, status], [status, leave]]) {
    expect(before.compareDocumentPosition(after) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  }
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '기다리는 화면' })).toHaveFocus()
    expect(document.title).toBe('기다리는 화면 · 머금')
  })
  expect(screen.getByRole('button', { name: '뒤로 가기' })).toBeEnabled()
})

test.each([
  ['NEW_COURSE', '강의 목록을 짜고 있어요'],
  ['PLAN_ROADMAP_COURSE', '강의 목록을 짜고 있어요'],
  ['REPLAN_COURSE', '강의 목록을 짜고 있어요'],
  ['NEW_ROADMAP', '로드맵 계획을 짜고 있어요'],
  ['REPLAN_ROADMAP', '로드맵 계획을 짜고 있어요'],
  ['MATERIAL', '강의 자료를 만들고 있어요'],
])('%s의 만드는 중 문구를 표시한다', async (type, message) => {
  open({ ...queued, type, status: 'RUNNING', subject: type.startsWith('NEW_') ? queued.subject : { title: '대상 제목' } })
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(message))
  expect(await screen.findByRole('heading', { level: 2 })).toHaveTextContent(type.startsWith('NEW_') ? '자바' : '대상 제목')
})

test.each([
  ['BEGINNER', 'WORK', '처음 배워요', '업무에 쓰려고요'],
  ['BASICS', 'INTERVIEW', '기초는 알아요', '면접 준비'],
  ['FAMILIAR', 'GENERAL', '익숙해요', '교양 · 관심'],
  ['OTHER', 'OTHER', '직접 쓴 수준', '직접 쓴 목적'],
])('새로 배우기 %s·%s는 화면 글을 쓴다', async (level, purpose, levelText, purposeText) => {
  open({ ...queued, type: 'NEW_ROADMAP', subject: { ...queued.subject, level, purpose, levelOther: '직접 쓴 수준', purposeOther: '직접 쓴 목적' } })
  expect(await screen.findByText(levelText)).toBeVisible()
  expect(screen.getByText(purposeText)).toBeVisible()
  expect(screen.queryByText(level)).not.toBeInTheDocument()
  expect(screen.queryByText(purpose)).not.toBeInTheDocument()
})

test.each([
  [{ type: 'COURSE', id: 31 }, '/courses/31'],
  [{ type: 'ROADMAP', id: 41 }, '/roadmaps/41'],
  [{ type: 'LESSON', id: 51, courseId: 31 }, '/courses/31/lessons/51'],
])('성공 결과 $type은 주소 %s로 기록을 바꾸어 이동한다', async (result, path) => {
  const { router } = open({ ...queued, status: 'SUCCEEDED', result })
  expect(await screen.findByRole('heading', { name: '결과 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe(path)
  expect(router.state.historyAction).toBe('REPLACE')
  await act(async () => { await router.navigate(-1) })
  expect(await screen.findByRole('heading', { name: '홈 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe('/')
})

test('일시적 실패는 서버 문구 그대로와 다시 시도·버리기를 보인다', async () => {
  open(temporary)
  expect(await screen.findByRole('alert')).toHaveTextContent('강의 목록을 만들지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent(temporary.error.message)
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '버리기' })).toBeEnabled()
  expect(screen.queryByText(/계속 만들어요/)).not.toBeInTheDocument()
})

test.each([
  ['NEW_COURSE', '강의 목록을 만들지 못했어요.'],
  ['PLAN_ROADMAP_COURSE', '강의 목록을 만들지 못했어요.'],
  ['REPLAN_COURSE', '강의 목록을 만들지 못했어요.'],
  ['NEW_ROADMAP', '로드맵 계획을 만들지 못했어요.'],
  ['REPLAN_ROADMAP', '로드맵 계획을 만들지 못했어요.'],
  ['MATERIAL', '강의 자료를 만들지 못했어요.'],
])('%s 실패는 무엇을 만들지 못했는지와 대상을 알린다', async (type, title) => {
  open({ ...temporary, type, subject: type.startsWith('NEW_') ? queued.subject : { title: '실패한 대상' } })
  expect(await screen.findByRole('alert')).toHaveTextContent(title)
  expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(type.startsWith('NEW_') ? '자바' : '실패한 대상')
})

test('입력 실패는 버리기만 보이고 문구 내용으로 버튼을 고르지 않는다', async () => {
  open({ ...inputFailure, error: { ...inputFailure.error, message: '다시 시도해 주세요라는 글이어도 버리기만' } })
  expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도해 주세요라는 글이어도 버리기만')
  expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '버리기' })).toBeEnabled()
})

test.each(['retry', 'discard'] as const)('%s 요청 중에는 두 버튼을 잠그고 중복 요청을 막는다', async (action) => {
  let resolveAction!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'POST'
    ? new Promise<Response>((resolve) => { resolveAction = resolve }) : Promise.resolve(response(temporary))))
  const { router } = open()
  fireEvent.click(await screen.findByRole('button', { name: action === 'retry' ? '다시 시도' : '버리기' }))
  await waitFor(() => {
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '버리기' })).toBeDisabled()
  })
  fireEvent.click(screen.getByRole('button', { name: '버리기' }))
  expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  await act(async () => { resolveAction(action === 'retry' ? response(queued) : new Response(null, { status: 204 })) })
  if (action === 'retry') expect(await screen.findByRole('status')).toHaveTextContent('차례를 기다리고 있어요')
  else {
    expect(await screen.findByRole('heading', { name: '홈 자리' })).toBeVisible()
    expect(router.state.location.pathname).toBe('/')
  }
})

test.each([
  ['retry', 'CREDIT_INSUFFICIENT', '크레딧이 부족합니다. 2026-12-01에 다시 채워집니다'],
  ['retry', 'TOO_MANY_IN_PROGRESS', '지금 만드는 것이 끝나면 다시 시도해 주세요'],
  ['discard', 'JOB_DISCARD_NOT_ALLOWED', '실패한 만들기만 버릴 수 있습니다'],
])('%s 동작 실패 %s는 서버 문구를 그대로 표시한다', async (action, code, message) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'POST'
    ? new Response(JSON.stringify({ code, message }), { status: 409 }) : response(temporary))))
  open()
  fireEvent.click(await screen.findByRole('button', { name: action === 'retry' ? '다시 시도' : '버리기' }))
  expect(await screen.findByText(message)).toBeVisible()
  expect(screen.getByText(action === 'retry' ? '다시 시도하지 못했어요.' : '버리지 못했어요.')).toBeVisible()
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
  expect(screen.queryByText(code)).not.toBeInTheDocument()
})

test('동작 중 연결 실패는 예외 원문 없이 안내하고 같은 동작을 다시 할 수 있다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(temporary)).mockRejectedValueOnce(new Error('secret-network-detail')).mockResolvedValue(response(queued)))
  open()
  fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
  expect(await screen.findByText('연결하지 못했어요. 다시 시도해 주세요.')).toBeVisible()
  expect(screen.queryByText(/secret-network-detail/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('status')).toHaveTextContent('차례를 기다리고 있어요')
  expect(screen.queryByText('다시 시도하지 못했어요.')).not.toBeInTheDocument()
})

test.each(['NOT_FOUND', 'network'])('%s 읽기 실패는 원인 안내와 다시 시도·홈으로를 제공한다', async (failure) => {
  const fetcher = vi.fn()
  if (failure === 'NOT_FOUND') fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ code: 'NOT_FOUND', message: '찾을 수 없습니다' }), { status: 404 }))
  else fetcher.mockRejectedValueOnce(new Error('technical-secret'))
  fetcher.mockResolvedValue(response(queued))
  vi.stubGlobal('fetch', fetcher)
  open()
  expect(await screen.findByRole('alert')).toHaveTextContent('작업을 불러오지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent(failure === 'NOT_FOUND' ? '찾을 수 없습니다' : '연결하지 못했어요. 다시 시도해 주세요.')
  expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/')
  expect(screen.queryByText(/technical-secret|NOT_FOUND/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('status')).toHaveTextContent('차례를 기다리고 있어요')
})

test('연결 실패의 홈으로는 실제 홈으로 이동한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
  const { router } = open()
  fireEvent.click(await screen.findByRole('link', { name: '홈으로' }))
  expect(await screen.findByRole('heading', { name: '홈 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe('/')
})

test('확장된 칸과 운영 정보·금지된 진행 표시·동작을 렌더링하지 않는다', async () => {
  open({ ...queued, progress: '72%', remainingTime: '40초', queuePosition: '2번째', model: 'secret-model', credit: 99, memo: 'secret-memo', feedback: 'secret-feedback', stage: 'secret-stage', futureField: 'secret-future' })
  await screen.findByText('자바')
  expect(screen.getByRole('main')).not.toHaveTextContent(/72%|40초|2번째|secret-|99|크레딧|토큰|비용|남은 시간|대기 순서|진행 단계|오래 걸리|멈춘|복습하기|취소|결과가/)
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
})

test.each([
  { status: 'FUTURE_STATUS' },
  { status: 'RUNNING', type: 'FUTURE_TYPE' },
  { status: 'SUCCEEDED', result: { type: 'FUTURE_RESULT', id: 99 } },
])('모르는 상태·종류·결과는 원래 값이나 잘못된 이동 없이 읽기 안내를 보인다: %j', async (extra) => {
  const { router } = open({ ...queued, ...extra })
  expect(await screen.findByRole('alert')).toHaveTextContent('작업을 불러오지 못했어요.')
  expect(screen.getByRole('main')).not.toHaveTextContent(/FUTURE_/)
  expect(router.state.location.pathname).toBe('/jobs/12')
})

test('모르는 수준·목적은 무시하고 주제·상태는 표시한다', async () => {
  open({ ...queued, subject: { topic: '자바', level: 'FUTURE_LEVEL', purpose: 'FUTURE_PURPOSE' } })
  expect(await screen.findByText('자바')).toBeVisible()
  expect(screen.getByRole('main')).not.toHaveTextContent(/FUTURE_/)
  expect(screen.getByRole('status')).toHaveTextContent('차례를 기다리고 있어요')
})

test('모르는 실패 묶음에는 알려진 묶음의 동작을 붙이지 않는다', async () => {
  open({ ...temporary, error: { code: 'FUTURE_CODE', message: '서버 안내' } })
  expect(await screen.findByText('서버 안내')).toBeVisible()
  expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '버리기' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '홈으로' })).toBeVisible()
  expect(screen.queryByText('FUTURE_CODE')).not.toBeInTheDocument()
})

test.each(['SUCCEEDED', 'FAILED'])('2초마다 QUEUED·RUNNING을 읽고 %s 뒤에는 멈춘다', async (status) => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(queued))
    .mockResolvedValueOnce(response({ ...queued, status: 'RUNNING' }))
    .mockImplementation(() => Promise.resolve(response(status === 'FAILED' ? temporary : { ...queued, status, result: { type: 'COURSE', id: 31 } }))))
  const { client } = open()
  await act(async () => { await vi.advanceTimersByTimeAsync(0) })
  expect(client.getQueryData(['jobs', '12'])).toMatchObject({ status: 'QUEUED' })
  await act(async () => { await vi.advanceTimersByTimeAsync(1999) })
  expect(fetch).toHaveBeenCalledTimes(1)
  await act(async () => { await vi.advanceTimersByTimeAsync(1) })
  expect(client.getQueryData(['jobs', '12'])).toMatchObject({ status: 'RUNNING' })
  await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
  expect(client.getQueryData(['jobs', '12'])).toMatchObject({ status })
  await act(async () => { await vi.advanceTimersByTimeAsync(20000) })
  expect(fetch).toHaveBeenCalledTimes(3)
})

test('실패를 다시 시도한 뒤 2초 간격의 다시 읽기가 재개된다', async () => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(temporary))
    .mockResolvedValueOnce(response(queued)).mockImplementation(() => Promise.resolve(response({ ...queued, status: 'RUNNING' }))))
  const { client } = open()
  await act(async () => { await vi.advanceTimersByTimeAsync(0) })
  await act(async () => { await vi.advanceTimersByTimeAsync(20000) })
  expect(fetch).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(0) })
  expect(client.getQueryData(['jobs', '12'])).toMatchObject({ status: 'QUEUED' })
  await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
  expect(fetch).toHaveBeenCalledTimes(3)
  expect(client.getQueryData(['jobs', '12'])).toMatchObject({ status: 'RUNNING' })
})
