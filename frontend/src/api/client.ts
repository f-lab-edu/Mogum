export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
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
    const code = body && typeof body === 'object' && 'code' in body && typeof body.code === 'string'
      ? body.code : fallbackCode
    const message = body && typeof body === 'object' && 'message' in body && typeof body.message === 'string'
      ? body.message : 'Request failed'
    throw new ApiError(response.status, code, message)
  }

  if (response.status === 204 || method === 'HEAD') return undefined as T
  return response.json() as Promise<T>
}
