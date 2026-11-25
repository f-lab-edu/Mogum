import { focusManager, onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { useHomeContinueLearning, useHomeJobs, useHomeReviews, useHomeStreak, useHomeTodos } from './useHome'

const sections = [
  { path: 'reviews', hook: useHomeReviews, data: { remainingCount: 3 } },
  { path: 'streak', hook: useHomeStreak, data: { days: 5, filledToday: false } },
  { path: 'continue-learning', hook: useHomeContinueLearning, data: { course: { id: 31, title: '자바', nextLesson: { id: 51, title: '변수' } } } },
  { path: 'todos', hook: useHomeTodos, data: { items: [{ type: 'COURSE_REVIEW', targetId: 32, title: '목록 검토' }] } },
  { path: 'jobs', hook: useHomeJobs, data: { jobs: [{ id: 12, type: 'NEW_COURSE', status: 'RUNNING', subject: { topic: '네트워크' } }] } },
] as const
const clients: QueryClient[] = []
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: 2, staleTime: Infinity } } })
  clients.push(client)
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}
function useAllSections() {
  return [useHomeReviews(), useHomeStreak(), useHomeContinueLearning(), useHomeTodos(), useHomeJobs()]
}
const response = (data: unknown) => new Response(JSON.stringify({ data }))
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.useRealTimers()
  vi.unstubAllGlobals()
  focusManager.setFocused(undefined)
  onlineManager.setOnline(true)
})

test.each(sections)('$path는 자기 경로와 독립 키로 서버 응답을 그대로 읽는다', async ({ path, hook, data }) => {
  const { client, wrapper } = setup()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(data)))
  const { result } = renderHook(() => hook(), { wrapper })
  await waitFor(() => {
    expect(result.current.data).toEqual(data)
    expect(client.getQueryData(['home', path])).toEqual(data)
  })
  expect(fetch).toHaveBeenCalledExactlyOnceWith(`/api/home/${path}`, expect.objectContaining({ method: 'GET', credentials: 'include', signal: expect.any(AbortSignal) }))
})

test.each(sections)('$path 실패는 공통 설정과 관계없이 자동 재시도하지 않는다', async ({ hook }) => {
  const { wrapper } = setup()
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('PRIVATE_NETWORK_DETAILS')))
  const { result } = renderHook(() => hook(), { wrapper })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(fetch).toHaveBeenCalledTimes(1)
})

test.each(sections)('$path의 수동 다시 읽기는 나머지 네 칸을 다시 읽지 않는다', async ({ path }) => {
  const { wrapper } = setup()
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url) => Promise.resolve(response(sections.find((section) => url === `/api/home/${section.path}`)?.data))))
  const { result } = renderHook(useAllSections, { wrapper })
  await waitFor(() => expect(result.current.every((query) => query.isSuccess)).toBe(true))
  await act(async () => { await result.current[sections.findIndex((section) => section.path === path)].refetch() })
  for (const section of sections) {
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === `/api/home/${section.path}`)).toHaveLength(section.path === path ? 2 : 1)
  }
})

test('모든 칸은 폴링·창 포커스·재연결로 읽지 않고 홈에 다시 들어오면 새로 읽는다', async () => {
  const { wrapper } = setup()
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url) => Promise.resolve(response(sections.find((section) => url === `/api/home/${section.path}`)?.data))))
  const first = renderHook(useAllSections, { wrapper })
  await waitFor(() => expect(first.result.current.every((query) => query.isSuccess)).toBe(true))
  vi.useFakeTimers()
  await act(async () => {
    focusManager.setFocused(false)
    focusManager.setFocused(true)
    onlineManager.setOnline(false)
    onlineManager.setOnline(true)
    await vi.advanceTimersByTimeAsync(60_000)
  })
  expect(fetch).toHaveBeenCalledTimes(5)
  vi.useRealTimers()
  first.unmount()
  vi.mocked(fetch).mockImplementation((url) => Promise.resolve(response(url === '/api/home/reviews' ? { remainingCount: 1 } : sections.find((section) => url === `/api/home/${section.path}`)?.data)))
  const second = renderHook(useAllSections, { wrapper })
  await waitFor(() => {
    expect(second.result.current[0].data).toEqual({ remainingCount: 1 })
    expect(fetch).toHaveBeenCalledTimes(10)
  })
})
