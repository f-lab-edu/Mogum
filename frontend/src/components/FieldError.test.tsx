import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import FieldError from './FieldError'

test.each([
  ['REQUIRED', '필수 입력이에요.'],
  ['TOO_LONG', '글자 수 상한을 넘었어요.'],
  ['INVALID_VALUE', '보기에서 골라 주세요.'],
  ['NOT_ALLOWED', '이 칸은 비워 주세요.'],
  ['FUTURE_REASON', '입력을 확인해 주세요.'],
])('칸 오류 %s를 코드 노출 없이 설명한다', (reason, message) => {
  render(<FieldError id="field-error" reason={reason} />)
  expect(screen.getByRole('alert')).toHaveTextContent(message)
  expect(screen.getByRole('alert')).toHaveAttribute('id', 'field-error')
  expect(screen.queryByText(reason)).not.toBeInTheDocument()
})
