import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { useReviewAction, useTodayReview } from './useReview'

const clients: QueryClient[] = []
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}
afterEach(() => { clients.forEach((client) => client.clear()); clients.length = 0; vi.unstubAllGlobals() })

test.each(['review', 'delete'] as const)('%s 성공은 진행 중인 코스 읽기를 취소하고 목록·상세를 즉시 읽지 않고 무효화한다', async (type) => {
  const { client, wrapper } = setup()
  client.setQueryData(['courses'], { courses: [] })
  client.setQueryData(['courses', '31'], { todayReviewCount: 2 })
  let late!: (data: unknown) => void
  const active = renderHook(() => useQuery({ queryKey: ['courses', '31'], queryFn: () => new Promise((resolve) => { late = resolve }) }), { wrapper })
  await waitFor(() => expect(active.result.current.isFetching).toBe(true))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
  const action = renderHook(() => useReviewAction(), { wrapper })
  await act(async () => { await action.result.current.mutateAsync(type === 'review' ? { type, cardId: 1, rating: 'GOOD' } : { type, cardId: 1 }) })
  await act(async () => { late({ todayReviewCount: 999 }) })
  expect(client.getQueryState(['courses'])?.isInvalidated).toBe(true)
  expect(client.getQueryState(['courses', '31'])?.isInvalidated).toBe(true)
  expect(client.getQueryData(['courses', '31'])).toEqual({ todayReviewCount: 2 })
  await waitFor(() => expect(active.result.current.isFetching).toBe(false))
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('읽기 실패를 자동 재시도하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('연결 실패')))
  const { wrapper } = setup()
  const { result } = renderHook(() => useTodayReview('31'), { wrapper })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(fetch).toHaveBeenCalledWith('/api/reviews/today?courseId=31', expect.objectContaining({ method: 'GET' }))
})
