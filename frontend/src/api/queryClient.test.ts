import { beforeEach, expect, test, vi } from 'vitest'
import { ApiError } from './client'
import { createAuthQueryClient } from './queryClient'
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
