import { beforeEach, expect, test, vi } from 'vitest'
import { ApiError } from './client'
import { createAuthQueryClient, retryApiQuery } from './queryClient'
import { hasConfirmedLogin } from '../lib/authNavigation'

beforeEach(() => sessionStorage.clear())

test('me 성공만 로그인 확인 이력으로 기록한다', async () => {
  const client = createAuthQueryClient(vi.fn())
  await client.fetchQuery({ queryKey: ['other'], queryFn: () => ({}) })
  expect(hasConfirmedLogin()).toBe(false)
  await client.fetchQuery({ queryKey: ['me'], queryFn: () => ({}) })
  expect(hasConfirmedLogin()).toBe(true)
  client.clear()
})

test.each(['me', 'curriculums'])('%s 쿼리의 401은 재시도 없이 공통 콜백을 부른다', async (key) => {
  const unauthorized = vi.fn()
  const client = createAuthQueryClient(unauthorized)
  const queryFn = vi.fn().mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '서버 문구'))
  await expect(client.fetchQuery({ queryKey: [key], queryFn })).rejects.toBeInstanceOf(ApiError)
  expect(queryFn).toHaveBeenCalledTimes(1)
  expect(unauthorized).toHaveBeenCalledTimes(1)
  client.clear()
})

test('변경 요청의 401도 공통 콜백을 부른다', async () => {
  const unauthorized = vi.fn()
  const client = createAuthQueryClient(unauthorized)
  const mutationFn = vi.fn().mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '서버 문구'))
  const mutation = client.getMutationCache().build(client, { mutationFn })
  await expect(mutation.execute(undefined)).rejects.toBeInstanceOf(ApiError)
  expect(mutationFn).toHaveBeenCalledTimes(1)
  expect(unauthorized).toHaveBeenCalledTimes(1)
  client.clear()
})

test.each(['CSRF_INVALID', 'TERMS_REQUIRED', 'ACCOUNT_WAITLISTED'])('403 %s는 로그인으로 보내거나 변경 요청을 재전송하지 않는다', async (code) => {
  const unauthorized = vi.fn()
  const client = createAuthQueryClient(unauthorized)
  const mutationFn = vi.fn().mockRejectedValue(new ApiError(403, code, '서버 문구'))
  const mutation = client.getMutationCache().build(client, { mutationFn })
  await expect(mutation.execute(undefined)).rejects.toBeInstanceOf(ApiError)
  expect(mutationFn).toHaveBeenCalledTimes(1)
  expect(unauthorized).not.toHaveBeenCalled()
  client.clear()
})

const retryCases = [
  { name: '네트워크 실패', error: new TypeError('Failed to fetch'), retry: true },
  { name: 'INTERNAL_ERROR', error: new ApiError(500, 'INTERNAL_ERROR', '서버 문구'), retry: true },
  { name: '200 INTERNAL_ERROR', error: new ApiError(200, 'INTERNAL_ERROR', '서버 문구'), retry: true },
  { name: 'NOT_FOUND', error: new ApiError(404, 'NOT_FOUND', '서버 문구'), retry: false },
  { name: 'RATE_LIMITED', error: new ApiError(429, 'RATE_LIMITED', '서버 문구'), retry: false },
  { name: '503 GLOBAL_BUDGET_EXHAUSTED', error: new ApiError(503, 'GLOBAL_BUDGET_EXHAUSTED', '서버 문구'), retry: false },
  { name: '모르는 code', error: new ApiError(503, 'FUTURE_ERROR', '서버 문구'), retry: false },
]

test.each(retryCases)('$name: 재시도는 code로 정하고 최대 2회다', ({ error, retry }) => {
  expect(retryApiQuery(0, error)).toBe(retry)
  expect(retryApiQuery(1, error)).toBe(retry)
  expect(retryApiQuery(2, error)).toBe(false)
  expect(retryApiQuery(3, error)).toBe(false)
})

test.each(retryCases)('$name 조회에 실제 재시도 규칙을 적용하고 변경 요청은 재시도하지 않는다', async ({ error, retry }) => {
  const unauthorized = vi.fn()
  const client = createAuthQueryClient(unauthorized)
  const queryFn = vi.fn().mockRejectedValue(error)
  await expect(client.fetchQuery({ queryKey: ['example'], queryFn, retryDelay: 0 })).rejects.toBe(error)
  expect(queryFn).toHaveBeenCalledTimes(retry ? 3 : 1)
  const mutationFn = vi.fn().mockRejectedValue(error)
  const mutation = client.getMutationCache().build(client, { mutationFn })
  await expect(mutation.execute(undefined)).rejects.toBe(error)
  expect(mutationFn).toHaveBeenCalledTimes(1)
  expect(unauthorized).not.toHaveBeenCalled()
  client.clear()
})

test.each([
  { status: 403, code: 'UNAUTHORIZED', redirects: true },
  { status: 401, code: 'FUTURE_ERROR', redirects: false },
])('$status $code: code만으로 로그인 콜백을 결정한다', async ({ status, code, redirects }) => {
  const unauthorized = vi.fn()
  const client = createAuthQueryClient(unauthorized)
  const error = new ApiError(status, code, '서버 문구')
  await expect(client.fetchQuery({ queryKey: ['me'], queryFn: () => Promise.reject(error) })).rejects.toBe(error)
  expect(unauthorized).toHaveBeenCalledTimes(redirects ? 1 : 0)
  const mutation = client.getMutationCache().build(client, { mutationFn: () => Promise.reject(error) })
  await expect(mutation.execute(undefined)).rejects.toBe(error)
  expect(unauthorized).toHaveBeenCalledTimes(redirects ? 2 : 0)
  client.clear()
})
