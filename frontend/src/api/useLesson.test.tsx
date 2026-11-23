import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { lessonKey, readLesson, useLesson, useLessonCompletion } from './useLesson'
import { courseKey, coursesKey } from './useCourse'
import { roadmapKey, roadmapsKey, useRoadmap, useRoadmaps } from './useRoadmap'

const lesson = { id: 51, courseId: 31, courseTitle: '자바', position: 1, title: '변수', completed: false, material: { body: '자료' } }
const clients: QueryClient[] = []
function setup() {
  const client = new QueryClient()
  clients.push(client)
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

test('강의 읽기는 ID를 인코딩하고 쿠키와 중단 신호를 보낸다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(lesson)))
  const signal = new AbortController().signal
  expect(await readLesson('51/a', signal)).toEqual(lesson)
  expect(fetch).toHaveBeenCalledWith('/api/lessons/51%2Fa', expect.objectContaining({ method: 'GET', credentials: 'include', signal }))
})

test('강의 읽기를 캐시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(lesson)))
  const { client, wrapper } = setup()
  const { result } = renderHook(() => useLesson('51'), { wrapper })
  await waitFor(() => expect(result.current.data).toEqual(lesson))
  expect(client.getQueryData(lessonKey('51'))).toEqual(lesson)
})

test('완료는 본문 없이 CSRF·쿠키를 보내고 진행 중 읽기를 취소한 뒤 캐시를 비운다', async () => {
  let finish!: (value: Response) => void
  document.cookie = 'XSRF-TOKEN=lesson-csrf; path=/'
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? new Promise<Response>((resolve) => { finish = resolve }) : Promise.resolve(response({ courseId: 31, courseCompleted: true }))))
  const { client, wrapper } = setup()
  client.setQueryData(lessonKey('51'), lesson)
  client.setQueryData(['courses', '31'], { progress: 'old' })
  const read = renderHook(() => useLesson('51'), { wrapper })
  const action = renderHook(() => useLessonCompletion('51'), { wrapper })
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
  await act(async () => { await action.result.current.mutateAsync() })
  expect(new Headers(vi.mocked(fetch).mock.calls[1][1]!.headers).get('X-XSRF-TOKEN')).toBe('lesson-csrf')
  expect(fetch).toHaveBeenLastCalledWith('/api/lessons/51/complete', expect.objectContaining({ method: 'POST', credentials: 'include' }))
  expect(vi.mocked(fetch).mock.calls.at(-1)?.[1]?.body).toBeUndefined()
  read.unmount()
  await act(async () => { finish(response(lesson)) })
  expect(client.getQueryData(lessonKey('51'))).toBeUndefined()
  expect(client.getQueryState(['courses', '31'])?.isInvalidated).toBe(true)
})

test.each([false, true])('코스 다 마침 %s와 관계없이 완료 뒤 코스·로드맵 목록과 상세 캐시를 무효화한다', async (courseCompleted) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ courseId: 31, courseCompleted })))
  const { client, wrapper } = setup()
  const keys = [coursesKey, courseKey('31'), roadmapsKey, roadmapKey('21'), roadmapKey('22')]
  keys.forEach((key) => client.setQueryData(key, { status: 'old' }))
  const action = renderHook(() => useLessonCompletion('51'), { wrapper })
  await act(async () => { await action.result.current.mutateAsync() })
  keys.forEach((key) => {
    expect(client.getQueryState(key)?.isInvalidated).toBe(true)
    expect(client.getQueryData(key)).toEqual({ status: 'old' })
  })
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('완료 뒤 진행 중인 로드맵 목록·상세 읽기를 취소하고 즉시 다시 읽지 않는다', async () => {
  const pending: { signal: AbortSignal; finish: (value: Response) => void }[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? new Promise<Response>((finish) => { pending.push({ signal: options.signal, finish }) })
    : Promise.resolve(response({ courseId: 31, courseCompleted: true }))))
  const { client, wrapper } = setup()
  const keys = [roadmapsKey, roadmapKey('21')]
  keys.forEach((key) => client.setQueryData(key, { status: 'cached' }))
  renderHook(() => useRoadmaps(), { wrapper })
  renderHook(() => useRoadmap('21'), { wrapper })
  const action = renderHook(() => useLessonCompletion('51'), { wrapper })
  await waitFor(() => expect(pending).toHaveLength(2))
  await act(async () => { await action.result.current.mutateAsync() })
  expect(pending.every(({ signal }) => signal.aborted)).toBe(true)
  await act(async () => { pending.forEach(({ finish }) => finish(response({ status: 'late' }))) })
  keys.forEach((key) => {
    expect(client.getQueryState(key)?.isInvalidated).toBe(true)
    expect(client.getQueryState(key)?.fetchStatus).toBe('idle')
    expect(client.getQueryData(key)).toEqual({ status: 'cached' })
  })
  expect(fetch).toHaveBeenCalledTimes(3)
})
