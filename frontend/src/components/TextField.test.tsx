import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import TextField from './TextField'

test.each([false, true])('여러 줄 %s 입력은 이름·필수 여부·오류를 연결하고 값을 전달한다', (multiline) => {
  const onChange = vi.fn()
  const view = render(<TextField label="주제" value="자바" onChange={onChange} required multiline={multiline} maxCodePoints={100} error="REQUIRED" />)
  const input = screen.getByRole('textbox', { name: '주제' })
  expect(input.tagName).toBe(multiline ? 'TEXTAREA' : 'INPUT')
  expect(input).toBeRequired()
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(input).toHaveAccessibleDescription(/필수 입력이에요/)
  fireEvent.change(input, { target: { value: '스프링' } })
  expect(onChange).toHaveBeenCalledWith('스프링')
  view.rerender(<TextField label="주제" value="자바" onChange={onChange} disabled multiline={multiline} />)
  expect(input).toBeDisabled()
  expect(input).not.toHaveAttribute('aria-invalid', 'true')
})

test.each([false, true])('여러 줄 %s 길이는 코드 포인트로 세어 상한과 초과를 알린다', (multiline) => {
  const view = render(<TextField label="메모" value={'😀'.repeat(50)} onChange={vi.fn()} multiline={multiline} maxCodePoints={50} />)
  const input = screen.getByRole('textbox', { name: '메모' })
  expect(input).toHaveAccessibleDescription('50 / 50자')
  expect(input).not.toHaveAttribute('maxlength')
  expect(input).not.toHaveAttribute('aria-invalid', 'true')
  view.rerender(<TextField label="메모" value={'😀'.repeat(51)} onChange={vi.fn()} multiline={multiline} maxCodePoints={50} />)
  expect(input).toHaveAccessibleDescription(/51 \/ 50자.*글자 수 상한을 넘었어요/)
  expect(input).toHaveAttribute('aria-invalid', 'true')
})
