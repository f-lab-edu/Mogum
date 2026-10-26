export type ApiErrorCode =
  | 'UNAUTHORIZED'
  | 'CSRF_INVALID'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'INVALID_REQUEST'
  | 'FIELD_INVALID'
  | 'INTERNAL_ERROR'
  | 'RATE_LIMITED'
  | 'TERMS_REQUIRED'
  | 'ACCOUNT_WAITLISTED'
  // Preserve unknown server codes while keeping known codes available for completion.
  | (string & Record<never, never>)

export type FieldViolationReason = 'TOO_LONG' | 'REQUIRED' | (string & Record<never, never>)

export interface FieldViolation {
  field: string
  reason: FieldViolationReason
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: ApiErrorCode,
    message: string,
    public readonly retryAfterSeconds?: number,
    public readonly fields?: FieldViolation[],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function apiOrigin() {
  return (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
}

export function googleLoginUrl() {
  return `${apiOrigin()}/auth/google`
}

function csrfToken() {
  const cookie = document.cookie.split(';').map((part) => part.trim())
    .find((part) => part.startsWith('XSRF-TOKEN='))
  // Spring expects the cookie's original value, without URL decoding.
  return cookie?.slice('XSRF-TOKEN='.length)
}

export async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (method !== 'GET' && method !== 'HEAD') {
    const token = csrfToken()
    if (token !== undefined) headers.set('X-XSRF-TOKEN', token)
  }

  const response = await fetch(`${apiOrigin()}/api${path}`, {
    ...options,
    method,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    const fallbackCode = response.status === 401 ? 'UNAUTHORIZED'
      : response.status >= 500 ? 'INTERNAL_ERROR' : 'INVALID_REQUEST'
    const code = isObject(body) && typeof body.code === 'string'
      ? body.code : fallbackCode
    const message = isObject(body) && typeof body.message === 'string'
      ? body.message : 'Request failed'
    const retryAfterSeconds = isObject(body) && typeof body.retryAfterSeconds === 'number'
      ? body.retryAfterSeconds : undefined
    const fields = isObject(body) && Array.isArray(body.fields) && body.fields.every(
      (violation): violation is FieldViolation => isObject(violation)
        && typeof violation.field === 'string' && typeof violation.reason === 'string',
    ) ? body.fields.map(({ field, reason }) => ({ field, reason })) : undefined
    throw new ApiError(response.status, code, message, retryAfterSeconds, fields)
  }

  if (response.status === 204 || method === 'HEAD') return undefined as T
  const body: unknown = await response.json().catch(() => null)
  if (!isObject(body) || !isObject(body.data)) {
    throw new ApiError(response.status, 'INTERNAL_ERROR', 'Invalid API response')
  }
  return body.data as T
}
