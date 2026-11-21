import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import ChoiceGroup from './ChoiceGroup'

test('기본 선택 없이 네이티브 라디오 묶음을 제공하고 고른 값·오류·잠금을 반영한다', async () => {
  const onChange = vi.fn()
  const options = [{ value: 'BEGINNER', label: '처음 배워요' }, { value: 'OTHER', label: '기타' }]
  const view = render(<ChoiceGroup label="지금 수준" options={options} value="" onChange={onChange} required />)
  const group = screen.getByRole('radiogroup', { name: '지금 수준' })
  const radios = within(group).getAllByRole('radio')
  expect(radios[0].parentElement?.parentElement).toHaveClass('grid', 'grid-cols-2', 'sm:grid-cols-4')
  expect(radios.every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
  expect(radios[0]).toHaveAttribute('type', 'radio')
  expect(radios[0]).toBeRequired()
  radios[0].focus()
  await waitFor(() => expect(radios[0]).toHaveFocus())
  fireEvent.click(radios[0])
  expect(onChange).toHaveBeenCalledWith('BEGINNER')
  view.rerender(<ChoiceGroup label="지금 수준" options={options} value="BEGINNER" onChange={onChange} error="INVALID_VALUE" disabled />)
  expect(radios[0]).toBeChecked()
  expect(radios[1]).not.toBeChecked()
  expect(radios[1]).toBeDisabled()
  expect(group).toHaveAccessibleDescription('보기에서 골라 주세요.')
})
