import { QueryClient, QueryClientProvider, QueryObserver } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { useRoadmapDeletion, readRoadmap, readRoadmaps, roadmapKey, roadmapsKey, useRoadmap, useRoadmaps } from './useRoadmap'

const roadmap = { id: 41, title: '자바 로드맵', description: '기초부터 실무까지', status: 'REVIEWING', courses: [] }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

test('로드맵 하나는 주소를 인코딩하고 쿠키·중단 신호로 읽는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(roadmap)))
  const signal = new AbortController().signal
  expect(await readRoadmap('41/a', signal)).toEqual(roadmap)
  expect(fetch).toHaveBeenCalledWith('/api/roadmaps/41%2Fa', expect.objectContaining({ credentials: 'include', signal }))
})

test('로드맵 목록은 쿠키·중단 신호로 한 번 읽는다', async () => {
  const data = { roadmaps: [roadmap] }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(data)))
  const signal = new AbortController().signal
  expect(await readRoadmaps(signal)).toEqual(data)
  expect(fetch).toHaveBeenCalledWith('/api/roadmaps', expect.objectContaining({ method: 'GET', credentials: 'include', signal }))
})

test.each(['detail', 'list'])('%s 읽기를 별도 키에 캐시하고 실패를 자동 재시도하지 않는다', async (kind) => {
  const data = kind === 'detail' ? roadmap : { roadmaps: [roadmap] }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(data)).mockRejectedValue(new Error('연결 실패')))
  const client = new QueryClient()
  clients.push(client)
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  const { result } = renderHook(() => kind === 'detail' ? useRoadmap('41') : useRoadmaps(), { wrapper })
  await waitFor(() => expect(result.current.data).toEqual(data))
  expect(result.current.isError).toBe(false)
  expect(client.getQueryData(kind === 'detail' ? roadmapKey('41') : roadmapsKey)).toEqual(data)
  await act(async () => { await result.current.refetch() })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(fetch).toHaveBeenCalledTimes(2)
})

test('로드맵 지우기는 인코딩·CSRF·본문 없는 DELETE 후 늦은 읽기를 막고 소속 코스까지 제거·목록과 홈을 무효화한다', async () => {
  const client = new QueryClient()
  clients.push(client)
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  const removed = [roadmapKey('41/a'), ['courses', '31'], ['courses', '32']]
  const invalidated = [roadmapsKey, ['courses'], ['home', 'todos'], ['home', 'jobs']]
  const pending: { signal: AbortSignal; finish: (data: unknown) => void }[] = []
  const reads = [...removed, ...invalidated].map((queryKey) => {
    client.setQueryData(queryKey, { old: true })
    return client.fetchQuery({ queryKey, queryFn: ({ signal }) => new Promise((finish) => { pending.push({ signal, finish }) }) }).catch(() => undefined)
  })
  for (const key of [roadmapKey('42'), ['courses', '33']]) client.setQueryData(key, { preserved: true })
  const reread = vi.fn()
  const subscriptions = invalidated.map((queryKey) => new QueryObserver(client, { queryKey, queryFn: reread, staleTime: Infinity }).subscribe(() => {}))
  document.cookie = 'XSRF-TOKEN=roadmap-delete; path=/'
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
  const { result } = renderHook(() => useRoadmapDeletion('41/a'), { wrapper })
  await act(async () => { await result.current.mutateAsync([31, 32]) })
  const [path, options] = vi.mocked(fetch).mock.calls[0]
  expect(path).toBe('/api/roadmaps/41%2Fa')
  expect(options).toMatchObject({ method: 'DELETE', credentials: 'include' })
  expect(options?.body).toBeUndefined()
  expect(new Headers(options?.headers).get('X-XSRF-TOKEN')).toBe('roadmap-delete')
  for (const read of pending) expect(read.signal.aborted).toBe(true)
  expect(reread).not.toHaveBeenCalled()
  subscriptions.forEach((unsubscribe) => unsubscribe())
  await act(async () => { pending.forEach((read) => read.finish({ late: true })); await Promise.all(reads) })
  for (const key of removed) expect(client.getQueryState(key)).toBeUndefined()
  for (const key of invalidated) {
    expect(client.getQueryData(key)).toEqual({ old: true })
    expect(client.getQueryState(key)?.isInvalidated).toBe(true)
  }
  for (const key of [roadmapKey('42'), ['courses', '33']]) expect(client.getQueryData(key)).toEqual({ preserved: true })
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('로드맵 지우기 실패는 상세·소속 코스·목록·홈을 보존하고 자동 재시도하지 않는다', async () => {
  const client = new QueryClient()
  clients.push(client)
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  const keys = [roadmapKey('41'), roadmapsKey, ['courses'], ['courses', '31'], ['home', 'todos']]
  for (const key of keys) client.setQueryData(key, { preserved: true })
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
  const { result } = renderHook(() => useRoadmapDeletion('41'), { wrapper })
  await act(async () => { await expect(result.current.mutateAsync([31])).rejects.toThrow('network') })
  for (const key of keys) {
    expect(client.getQueryData(key)).toEqual({ preserved: true })
    expect(client.getQueryState(key)?.isInvalidated).toBe(false)
  }
  expect(fetch).toHaveBeenCalledTimes(1)
})
