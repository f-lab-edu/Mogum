import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { readRoadmap, readRoadmaps, roadmapKey, roadmapsKey, useRoadmap, useRoadmaps } from './useRoadmap'

const roadmap = { id: 41, title: '자바 로드맵', description: '기초부터 실무까지', status: 'REVIEWING', courses: [] }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
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
