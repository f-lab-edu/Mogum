import { expect, test } from 'vitest'
import { messages } from './messages'

test('로그인 안내와 공통 실패 문구를 설계 문구로 모은다', () => {
  expect(messages.loginFailed).toBe('로그인하지 못했어요. 다시 시도해 주세요.')
  expect(messages).not.toHaveProperty('notAllowed')
  expect(messages.sessionExpired).toBe('다시 로그인해 주세요.')
  expect(messages.rateLimited).toBe('잠시 후 다시 시도해 주세요.')
  expect(messages.loadFailed).toBe('불러오지 못했어요.')
})
