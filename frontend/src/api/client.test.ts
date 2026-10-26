import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, apiFetch, googleLoginUrl } from './client'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"data":{}}')))
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

test.each([200, 202])('성공 %s는 data 객체를 반환한다', async (status) => {
  const data = { email: 'admin@example.com', cards: [], waitlistPosition: null }
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ data }), { status }))
  await expect(apiFetch<typeof data>('/example')).resolves.toEqual(data)
})

test.each([
  [204, 'GET'],
  [200, 'HEAD'],
  [200, 'head'],
])('%s %s는 본문 없이 undefined를 반환한다', async (status, method) => {
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status }))
  await expect(apiFetch('/example', { method })).resolves.toBeUndefined()
})

test.each([
  [200, '<html>index.html</html>'],
  [202, '{}'],
  [200, '{"email":"unwrapped@example.com"}'],
  [200, '{"data":[]}'],
  [200, '{"data":null}'],
  [200, '{"data":"text"}'],
  [200, '{"data":1}'],
  [200, '{"data":true}'],
  [200, 'null'],
  [200, '[]'],
])('성공 %s라도 계약에 맞지 않는 %s는 INTERNAL_ERROR다', async (status, body) => {
  vi.mocked(fetch).mockResolvedValue(new Response(body, { status }))
  const error = await apiFetch('/me').catch((error: unknown) => error)
  expect(error).toBeInstanceOf(ApiError)
  expect(error).toMatchObject({ status, code: 'INTERNAL_ERROR' })
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('오류 본문의 retryAfterSeconds와 fields를 보존한다', async () => {
  const body = { code: 'STRUCTURAL_LIMIT', message: '서버 문구', retryAfterSeconds: 0, fields: ['topic', 'purpose'] }
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify(body), { status: 400 }))
  await expect(apiFetch('/example')).rejects.toMatchObject({ status: 400, ...body })
})

test.each([
  {},
  { retryAfterSeconds: '30', fields: [1, 2] },
  { retryAfterSeconds: null, fields: 'topic' },
  { retryAfterSeconds: {}, fields: ['topic', 1] },
])('오류 선택 필드가 없거나 타입이 틀리면 담지 않는다: %j', async (optional) => {
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'INVALID_REQUEST', message: '서버 문구', ...optional }), { status: 400 }))
  const error = await apiFetch('/example').catch((error: unknown) => error)
  expect(error).toBeInstanceOf(ApiError)
  expect(error).toMatchObject({ status: 400, code: 'INVALID_REQUEST', message: '서버 문구' })
  expect(error).toHaveProperty('retryAfterSeconds', undefined)
  expect(error).toHaveProperty('fields', undefined)
})

test('모르는 오류 code도 원문대로 보존한다', async () => {
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'FUTURE_ERROR', message: '서버 문구' }), { status: 503 }))
  await expect(apiFetch('/example')).rejects.toMatchObject({ status: 503, code: 'FUTURE_ERROR', message: '서버 문구' })
})

test('code와 message 타입이 틀리면 대체값을 쓴다', async () => {
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 1, message: [] }), { status: 400 }))
  await expect(apiFetch('/example')).rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST', message: 'Request failed' })
})

test.each([[401, 'UNAUTHORIZED'], [403, 'INVALID_REQUEST'], [404, 'INVALID_REQUEST'], [502, 'INTERNAL_ERROR']])('JSON이 아닌 실패 %s도 HTTP 상태를 보존한다', async (status, code) => {
  vi.mocked(fetch).mockResolvedValue(new Response('Bad gateway', { status }))
  await expect(apiFetch('/me')).rejects.toMatchObject({ status, code })
})
