import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, test } from 'vitest'
import { ApiError } from '../api/client'
import CreationBlockedNotice, { isCreationBlockedError } from './CreationBlockedNotice'

test.each([
  ['CREDIT_INSUFFICIENT', '크레딧이 부족합니다. 2026-12-18에 다시 채워집니다'],
  ['TOO_MANY_IN_PROGRESS', '지금 만드는 것이 끝나면 다시 시도해 주세요'],
])('%s 안내는 서버 문구 그대로 보이고 크레딧 부족에만 내 정보 링크를 둔다', (code, message) => {
  render(<MemoryRouter><CreationBlockedNotice error={new ApiError(409, code, message)} /></MemoryRouter>)
  const notice = screen.getByRole('status')
  const title = within(notice).getByText('지금은 만들 수 없어요')
  expect(title).toHaveClass('text-text')
  expect(title).not.toHaveClass('text-error')
  expect(within(notice).getByText(message).textContent).toBe(message)
  const link = within(notice).queryByRole('link', { name: '남은 크레딧 보기' })
  if (code === 'CREDIT_INSUFFICIENT') expect(link).toHaveAttribute('href', '/profile')
  else expect(link).not.toBeInTheDocument()
  expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
  expect(notice.textContent).toBe(`지금은 만들 수 없어요${message}${code === 'CREDIT_INSUFFICIENT' ? '남은 크레딧 보기' : ''}`)
  expect(notice).not.toHaveTextContent(code)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('서버가 바꾼 문구도 날짜나 안내를 덧붙이지 않고 그대로 보인다', () => {
  const message = '서버가 정한 새 안내 · 2027-01-02'
  render(<MemoryRouter><CreationBlockedNotice error={new ApiError(409, 'CREDIT_INSUFFICIENT', message)} /></MemoryRouter>)
  expect(screen.getByText(message).textContent).toBe(message)
})

test.each([
  [new ApiError(409, 'CREDIT_INSUFFICIENT', '서버 안내'), true],
  [new ApiError(409, 'TOO_MANY_IN_PROGRESS', '서버 안내'), true],
  [new ApiError(400, 'FIELD_INVALID', '서버 안내'), false],
  [new ApiError(409, 'REPLAN_NOT_ALLOWED', '서버 안내'), false],
  [new ApiError(409, 'FUTURE_CODE', '서버 안내'), false],
  [new Error('CREDIT_INSUFFICIENT'), false],
  [null, false],
])('오류 %s는 두 서버 code일 때만 만들 수 없음으로 가른다', (error, expected) => {
  expect(isCreationBlockedError(error)).toBe(expected)
})
