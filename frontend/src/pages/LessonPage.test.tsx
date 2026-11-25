import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import LessonPage from './LessonPage'

const mermaid = vi.hoisted(() => ({ initialize: vi.fn(), render: vi.fn() }))
vi.mock('mermaid', () => ({ default: mermaid }))
const lesson = { id: 51, courseId: 31, courseTitle: '자바 기초', position: 1, title: '변수', completed: false,
  material: { body: '## 핵심 개념\n\n**값**을 담아요.\n\n$x^2$\n\n```mermaid\ngraph LR\nA-->B\n```' } }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function failure(code: string, message: string) { return new Response(JSON.stringify({ code, message }), { status: 409 }) }
function open(data: unknown = lesson, cached?: unknown) {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  mermaid.render.mockResolvedValue({ svg: '<svg aria-label="변수 그림" viewBox="0 0 200 100"></svg>' })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  if (cached) client.setQueryData(['lessons', '51'], cached)
  client.setQueryData(['courses', '31'], { title: '이전 진도' })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/', element: <h1>홈 자리</h1> },
      { path: '/courses/:courseId/lessons/:lessonId', element: <AppLayout title="강의"><LessonPage /></AppLayout> },
      { path: '/courses/:courseId', element: <h1>코스 자리</h1> },
      { path: '/courses/:courseId/done', element: <h1>축하 자리</h1> },
      { path: '/jobs/:jobId', element: <h1>기다리는 화면 자리</h1> },
    ],
  }], { initialEntries: ['/', '/courses/31/lessons/51'] })
  render(<StrictMode><QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider></StrictMode>)
  return { client, router }
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

test.each([false, true])('강의 제목·본문·수식·그림과 완료 여부 %s를 표시한다', async (completed) => {
  open({ ...lesson, completed, model: 'secret-model', tokens: 98765, cards: [{ question: 'secret-card' }] })
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '1강 · 변수' })).toBeVisible()
    expect(screen.getByText('값', { selector: 'strong' })).toBeVisible()
    expect(document.querySelector('.katex')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '다이어그램' }).querySelector('svg')).toBeInTheDocument()
    if (completed) {
      expect(screen.queryByRole('button', { name: '학습 완료' })).not.toBeInTheDocument()
      expect(screen.getByText('학습 완료한 강의예요.')).toBeVisible()
    } else {
      expect(screen.getByRole('button', { name: '학습 완료' })).toBeEnabled()
      expect(screen.getByText('학습 완료는 되돌릴 수 없어요. 다시 열어 읽을 수 있어요.')).toBeVisible()
    }
    expect(screen.getByRole('heading', { name: '강의' })).toHaveFocus()
    expect(screen.getByRole('main')).not.toHaveTextContent(/secret-|98765|토큰|비용|모델|카드가 복습|다시 만들기|첫 복습/)
  })
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
})

test.each(['QUEUED', 'RUNNING', 'FAILED'])('본문 없이 %s 작업이 있으면 기다리는 화면으로 교체한다', async (status) => {
  const { router } = open({ ...lesson, material: undefined, job: { id: 77, status } })
  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/jobs/77')
    expect(router.state.historyAction).toBe('REPLACE')
  })
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
  await act(async () => { await router.navigate(-1) })
  expect(screen.getByRole('heading', { name: '홈 자리' })).toBeVisible()
})

test('자료와 작업이 없으면 StrictMode에서도 받기를 한 번만 보내고 기다리는 화면으로 교체한다', async () => {
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? Promise.resolve(response({ ...lesson, material: undefined }))
    : new Promise<Response>((resolve) => { finish = resolve })))
  const { router } = open()
  await waitFor(() => {
    expect(screen.getByRole('status')).toHaveTextContent('강의 자료를 요청하고 있어요.')
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  })
  expect(fetch).toHaveBeenLastCalledWith('/api/jobs', expect.objectContaining({ method: 'POST', body: JSON.stringify({ type: 'MATERIAL', lessonId: 51 }) }))
  await act(async () => { finish(response({ id: 77 })) })
  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/jobs/77')
    expect(router.state.historyAction).toBe('REPLACE')
  })
})

test.each([
  ['CREDIT_INSUFFICIENT', '크레딧이 부족합니다. 2026-12-01에 다시 채워집니다'],
  ['TOO_MANY_IN_PROGRESS', '지금 만드는 것이 끝나면 다시 시도해 주세요'],
])('받기 거절 %s는 공통 안내와 코스로 돌아가는 길을 보인다', async (code, message) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET'
    ? response({ ...lesson, material: undefined }) : failure(code, message))))
  const { router } = open()
  await screen.findByText('지금은 만들 수 없어요')
  await waitFor(() => {
    const notice = screen.getByRole('status')
    expect(notice).toHaveTextContent(message)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
    const link = within(notice).queryByRole('link', { name: '남은 크레딧 보기' })
    if (code === 'CREDIT_INSUFFICIENT') expect(link).toHaveAttribute('href', '/profile')
    else expect(link).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '코스로 돌아가기' })).toHaveAttribute('href', '/courses/31')
    expect(screen.queryByText(code)).not.toBeInTheDocument()
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  })
  fireEvent.click(screen.getByRole('link', { name: '코스로 돌아가기' }))
  await screen.findByRole('heading', { name: '코스 자리' })
  await waitFor(() => expect(router.state.location.pathname).toBe('/courses/31'))
})

test.each(['LESSON_NOT_OPEN', 'network'])('받기 실패 %s는 기존 실패 안내와 돌아가기 링크를 유지한다', async (kind) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => {
    if (options.method === 'GET') return Promise.resolve(response({ ...lesson, material: undefined }))
    return kind === 'network' ? Promise.reject(new Error('secret-network-detail'))
      : Promise.resolve(failure(kind, '코스를 시작하고 앞 강의를 학습 완료하면 열 수 있어요'))
  }))
  open()
  await screen.findByRole('alert')
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('강의 자료를 요청하지 못했어요.')
    expect(screen.getByRole('alert')).toHaveTextContent(kind === 'network' ? '연결하지 못했어요. 다시 시도해 주세요.' : '코스를 시작하고 앞 강의를 학습 완료하면 열 수 있어요')
    expect(screen.getByRole('link', { name: '코스로 돌아가기' })).toHaveAttribute('href', '/courses/31')
    expect(screen.queryByText('지금은 만들 수 없어요')).not.toBeInTheDocument()
    expect(screen.queryByText('secret-network-detail')).not.toBeInTheDocument()
  })
})

test('읽기 거절은 서버 문구와 코스 링크를 보이고 받기를 하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(failure('LESSON_NOT_OPEN', '앞 강의를 학습 완료해 주세요'))))
  open()
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('앞 강의를 학습 완료해 주세요')
    expect(screen.getByRole('link', { name: '코스로 돌아가기' })).toHaveAttribute('href', '/courses/31')
    expect(screen.queryByText('LESSON_NOT_OPEN')).not.toBeInTheDocument()
  })
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
})

test.each([false, true])('완료 중 중복을 막고 코스 다 마침 %s에 따라 교체 이동하며 캐시를 비운다', async (courseCompleted) => {
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? Promise.resolve(response(lesson)) : new Promise<Response>((resolve) => { finish = resolve })))
  const { router, client } = open()
  const button = await screen.findByRole('button', { name: '학습 완료' })
  fireEvent.click(button)
  await waitFor(() => expect(button).toBeDisabled())
  fireEvent.click(button)
  expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  expect(fetch).toHaveBeenLastCalledWith('/api/lessons/51/complete', expect.objectContaining({ method: 'POST' }))
  expect(vi.mocked(fetch).mock.calls.at(-1)?.[1]?.body).toBeUndefined()
  await act(async () => { finish(response({ courseId: 31, courseCompleted })) })
  await waitFor(() => {
    expect(router.state.location.pathname).toBe(courseCompleted ? '/courses/31/done' : '/courses/31')
    expect(router.state.historyAction).toBe('REPLACE')
    expect(client.getQueryData(['lessons', '51'])).toBeUndefined()
    expect(client.getQueryState(['courses', '31'])?.isInvalidated).toBe(true)
  })
  await act(async () => { await router.navigate(-1) })
  expect(screen.getByRole('heading', { name: '홈 자리' })).toBeVisible()
})

test('완료 실패는 서버 문구를 표시하고 본문을 보존하며 다시 누를 수 있다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET'
    ? response(lesson) : failure('INTERNAL_ERROR', '완료를 저장하지 못했어요'))))
  const { router } = open()
  fireEvent.click(await screen.findByRole('button', { name: '학습 완료' }))
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('완료를 저장하지 못했어요')
    expect(screen.getByRole('button', { name: '학습 완료' })).toBeEnabled()
    expect(screen.getByText('값', { selector: 'strong' })).toBeVisible()
    expect(screen.queryByText('INTERNAL_ERROR')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/courses/31/lessons/51')
  })
})

test('기다리는 화면에서 돌아왔을 때 낡은 작업 캐시 대신 다시 읽은 본문을 표시한다', async () => {
  const { router } = open(lesson, { ...lesson, material: undefined, job: { id: 77, status: 'RUNNING' } })
  await waitFor(() => {
    expect(screen.getByText('값', { selector: 'strong' })).toBeVisible()
    expect(router.state.location.pathname).toBe('/courses/31/lessons/51')
  })
})
