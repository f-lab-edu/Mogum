import type { NavigateFunction } from 'react-router-dom'

const returnPathKey = 'mogum:return-path'
// Historical UI state for U1 wording only; this never determines authentication.
const confirmedLoginKey = 'mogum:login-confirmed'

export function hasConfirmedLogin() {
  return sessionStorage.getItem(confirmedLoginKey) === 'true'
}

export function markLoginConfirmed() {
  sessionStorage.setItem(confirmedLoginKey, 'true')
}

export function rememberReturnPath(path: string) {
  sessionStorage.setItem(returnPathKey, path)
}

export function takeReturnPath(): string | null {
  const path = sessionStorage.getItem(returnPathKey)
  sessionStorage.removeItem(returnPathKey)
  if (!path?.startsWith('/') || path.startsWith('//')) return null

  try {
    const url = new URL(path, window.location.origin)
    if (url.origin !== window.location.origin || /^\/login\/*$/.test(url.pathname)) return null
    return `${url.pathname}${url.search}${url.hash}`
  } catch {
    return null
  }
}

export function redirectToLogin(navigate: NavigateFunction) {
  const { pathname, search, hash } = window.location
  // Concurrent requests may report 401 after the first request already navigated.
  if (pathname === '/login') return
  rememberReturnPath(`${pathname}${search}${hash}`)
  void navigate('/login', { replace: true, state: { sessionExpired: hasConfirmedLogin() } })
}
