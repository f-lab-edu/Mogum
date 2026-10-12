import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, apiFetch, googleLoginUrl } from './client'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')))
  vi.stubEnv('VITE_API_BASE_URL', '')
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
})

describe('API 주소와 세션 쿠키', () => {
  test.each(['', 'https://api.mogum.example'])('출처 %s에서 API와 로그인 주소를 만든다', async (origin) => {
    vi.stubEnv('VITE_API_BASE_URL', origin)
    await apiFetch('/me')
    expect(fetch).toHaveBeenCalledWith(`${origin}/api/me`, expect.objectContaining({ credentials: 'include' }))
    expect(googleLoginUrl()).toBe(`${origin}/auth/google`)
  })

  test('출처 끝의 슬래시를 중복하지 않는다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.mogum.example/')
    await apiFetch('/me', { credentials: 'omit' })
    expect(fetch).toHaveBeenCalledWith('https://api.mogum.example/api/me', expect.objectContaining({ credentials: 'include' }))
    expect(googleLoginUrl()).toBe('https://api.mogum.example/auth/google')
  })
})

test.each(['POST', 'PUT', 'PATCH', 'DELETE', 'post'])('%s는 쿠키 원본을 CSRF 헤더에 싣는다', async (method) => {
  document.cookie = 'another=ignored; Path=/'
  document.cookie = 'XSRF-TOKEN=raw%2Bvalue%3D; Path=/'
  await apiFetch('/example', { method, headers: { 'Content-Type': 'application/json' }, body: '{}' })
  const headers = new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers)
  expect(headers.get('X-XSRF-TOKEN')).toBe('raw%2Bvalue%3D')
  expect(headers.get('Content-Type')).toBe('application/json')
})

test.each(['GET', 'HEAD'])('%s에는 CSRF 헤더를 추가하지 않는다', async (method) => {
  document.cookie = 'XSRF-TOKEN=token; Path=/'
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))
  await apiFetch('/example', { method })
  expect(new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers).has('X-XSRF-TOKEN')).toBe(false)
})

test('쿠키가 없으면 CSRF 헤더를 만들지 않는다', async () => {
  await apiFetch('/example', { method: 'POST' })
  expect(new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers).has('X-XSRF-TOKEN')).toBe(false)
})

test.each([[401, 'UNAUTHORIZED'], [403, 'CSRF_INVALID']])('실패 %s의 상태와 code, message를 보존하고 재전송하지 않는다', async (status, code) => {
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code, message: '서버 문구' }), { status }))
  const error = await apiFetch('/example', { method: 'POST' }).catch((error: unknown) => error)
  expect(error).toBeInstanceOf(ApiError)
  expect(error).toMatchObject({ status, code, message: '서버 문구' })
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('JSON이 아닌 실패 응답도 HTTP 상태를 보존한다', async () => {
  vi.mocked(fetch).mockResolvedValue(new Response('Bad gateway', { status: 502 }))
  await expect(apiFetch('/me')).rejects.toMatchObject({ status: 502, code: 'INTERNAL_ERROR' })
})
