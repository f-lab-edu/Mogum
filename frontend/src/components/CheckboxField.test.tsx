import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import CheckboxField from './CheckboxField'

test('체크는 기본 꺼짐·설명·변경·잠금 상태를 제공한다', () => {
  const onChange = vi.fn()
  const view = render(<CheckboxField label="로드맵으로 만들기" checked={false} onChange={onChange} description={<p>여러 코스로 나눠 배워요.</p>} />)
  const checkbox = screen.getByRole('checkbox', { name: '로드맵으로 만들기' })
  expect(checkbox).not.toBeChecked()
  expect(checkbox).toHaveAccessibleDescription('여러 코스로 나눠 배워요.')
  fireEvent.click(checkbox)
  expect(onChange).toHaveBeenCalledWith(true)
  view.rerender(<CheckboxField label="로드맵으로 만들기" checked onChange={onChange} disabled />)
  expect(checkbox).toBeChecked()
  expect(checkbox).toBeDisabled()
})
