import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import Button from './Button'

test('기본 버튼은 폼을 제출하지 않고 클릭 동작을 실행한다', () => {
  const submit = vi.fn((event) => event.preventDefault())
  const click = vi.fn()
  render(<form onSubmit={submit}><Button onClick={click}>다시 시도</Button></form>)

  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

  expect(click).toHaveBeenCalledOnce()
  expect(submit).not.toHaveBeenCalled()
})

test('막힌 버튼은 클릭을 실행하지 않고 다시 활성화할 수 있다', () => {
  const click = vi.fn()
  const view = render(<Button disabled onClick={click}>다시 시도</Button>)
  const button = screen.getByRole('button', { name: '다시 시도' })

  expect(button).toBeDisabled()
  fireEvent.click(button)
  expect(click).not.toHaveBeenCalled()

  view.rerender(<Button onClick={click}>다시 시도</Button>)
  button.focus()
  expect(button).toHaveFocus()
  fireEvent.click(button)
  expect(click).toHaveBeenCalledOnce()
})

test('보조 버튼은 기본 버튼보다 약한 모양이며 같은 클릭·잠금·포커스를 지원한다', () => {
  const click = vi.fn()
  const view = render(<Button variant="secondary" disabled onClick={click}>로그아웃</Button>)
  const button = screen.getByRole('button', { name: '로그아웃' })
  expect(button).toHaveClass('bg-surface-soft', 'text-text-secondary', 'border-border')
  expect(button).not.toHaveClass('bg-primary', 'shadow-button')
  fireEvent.click(button)
  expect(click).not.toHaveBeenCalled()
  view.rerender(<Button variant="secondary" onClick={click}>로그아웃</Button>)
  button.focus()
  expect(button).toHaveFocus()
  fireEvent.click(button)
  expect(click).toHaveBeenCalledOnce()
})
