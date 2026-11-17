import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { discardJob, jobKey, readJob, receiveJob, retryJob, useJob, useJobAction } from './useJob'

const queued = { id: 12, type: 'NEW_COURSE', status: 'QUEUED', subject: { topic: '자바', level: 'BEGINNER', purpose: 'WORK' } }
const clients: QueryClient[] = []
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}
function response(data: unknown) { return new Response(JSON.stringify({ data })) }

test.each(['NEW_COURSE', 'NEW_ROADMAP'] as const)('%s 받기는 평평한 JSON·쿠키·CSRF로 보내고 작업을 반환한다', async (type) => {
  document.cookie = 'XSRF-TOKEN=test-csrf; path=/'
  const request = { type, topic: '자바', level: 'OTHER' as const, levelOther: '실무 경험', purpose: 'WORK' as const }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...queued, type })))
  expect(await receiveJob(request)).toEqual({ ...queued, type })
  expect(fetch).toHaveBeenCalledWith('/api/jobs', expect.objectContaining({ method: 'POST', credentials: 'include', body: JSON.stringify(request) }))
  const headers = new Headers(vi.mocked(fetch).mock.calls[0][1]!.headers)
  expect(headers.get('Content-Type')).toBe('application/json')
  expect(headers.get('X-XSRF-TOKEN')).toBe('test-csrf')
})
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

test('작업 읽기는 쿠키·중단 신호를 보내고 확장된 응답을 읽는다', async () => {
  const signal = new AbortController().signal
  const data = { ...queued, futureField: '알 수 없는 칸' }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(data)))
  expect(await readJob('12', signal)).toEqual(data)
  expect(fetch).toHaveBeenCalledWith('/api/jobs/12', expect.objectContaining({ method: 'GET', credentials: 'include', signal }))
})

test.each(['retry', 'discard'] as const)('%s 요청은 본문 없이 apiFetch의 CSRF·쿠키 경로를 쓴다', async (action) => {
  document.cookie = 'XSRF-TOKEN=test-csrf; path=/'
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(action === 'retry' ? response(queued) : new Response(null, { status: 204 })))
  const result = await (action === 'retry' ? retryJob('12') : discardJob('12'))
  expect(result).toEqual(action === 'retry' ? queued : undefined)
  expect(fetch).toHaveBeenCalledWith(`/api/jobs/12/${action}`, expect.objectContaining({ method: 'POST', credentials: 'include' }))
  const options = vi.mocked(fetch).mock.calls[0][1]!
  expect(new Headers(options.headers).get('X-XSRF-TOKEN')).toBe('test-csrf')
  expect(options.body).toBeUndefined()
})

test('다시 시도 응답으로 작업 읽기 캐시 전체를 교체한다', async () => {
  const { client, wrapper } = setup()
  client.setQueryData(jobKey('12'), { ...queued, status: 'FAILED', error: { code: 'JOB_TEMPORARY_FAILURE', message: '실패' } })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(queued)))
  const { result } = renderHook(() => useJobAction('12'), { wrapper })
  act(() => result.current.mutate('retry'))
  await waitFor(() => expect(result.current.isSuccess).toBe(true))
  expect(client.getQueryData(jobKey('12'))).toEqual(queued)
})

test('버리기 성공은 해당 작업 캐시만 지운다', async () => {
  const { client, wrapper } = setup()
  client.setQueryData(jobKey('12'), { ...queued, status: 'FAILED' })
  client.setQueryData(jobKey('13'), queued)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
  const { result } = renderHook(() => useJobAction('12'), { wrapper })
  act(() => result.current.mutate('discard'))
  await waitFor(() => expect(result.current.isSuccess).toBe(true))
  expect(client.getQueryData(jobKey('12'))).toBeUndefined()
  expect(client.getQueryData(jobKey('13'))).toEqual(queued)
})

test('늦은 작업 읽기는 다시 시도 성공의 캐시를 덮어쓰지 않는다', async () => {
  const { wrapper } = setup()
  let finishRead!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? new Promise<Response>((resolve) => { finishRead = resolve }) : Promise.resolve(response(queued))))
  const query = renderHook(() => useJob('12'), { wrapper })
  const action = renderHook(() => useJobAction('12'), { wrapper })
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
  act(() => action.result.current.mutate('retry'))
  await waitFor(() => expect(action.result.current.isSuccess).toBe(true))
  await act(async () => { finishRead(response({ ...queued, status: 'FAILED' })) })
  expect(query.result.current.data).toEqual(queued)
})
