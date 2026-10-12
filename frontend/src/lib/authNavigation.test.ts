import { beforeEach, expect, test, vi } from 'vitest'
import { hasConfirmedLogin, markLoginConfirmed, redirectToLogin, rememberReturnPath, takeReturnPath } from './authNavigation'

beforeEach(() => sessionStorage.clear())

test('확인 이력은 이 탭의 화면 상태이며 사용자 정보는 저장하지 않는다', () => {
  expect(hasConfirmedLogin()).toBe(false)
  markLoginConfirmed()
  expect(hasConfirmedLogin()).toBe(true)
  expect(sessionStorage.length).toBe(1)
})

test('경로, 쿼리, 해시를 한 번만 되살린다', () => {
  rememberReturnPath('/curriculums/12?tab=1#lesson')
  expect(takeReturnPath()).toBe('/curriculums/12?tab=1#lesson')
  expect(takeReturnPath()).toBeNull()
})

test.each(['//evil.example', 'https://evil.example', '/login', '/login?error=x#alert', '/login/', '/foo/../login', '/\\evil.example', '/\n/evil.example'])('위험하거나 로그인으로 돌아가는 주소 %s를 버린다', (path) => {
  rememberReturnPath(path)
  expect(takeReturnPath()).toBeNull()
  expect(takeReturnPath()).toBeNull()
})

test('이미 로그인에 도착한 뒤의 늦은 401은 원래 주소와 안내를 덮어쓰지 않는다', () => {
  rememberReturnPath('/curriculums/12')
  window.history.replaceState(null, '', '/login')
  const navigate = vi.fn()
  redirectToLogin(navigate)
  expect(navigate).not.toHaveBeenCalled()
  expect(takeReturnPath()).toBe('/curriculums/12')
})
