import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { expect, test, vi } from 'vitest'
import InlineConfirmation from './InlineConfirmation'

function Example({ disabled = false, confirm = () => {} }: { disabled?: boolean; confirm?: () => void }) {
  const [open, setOpen] = useState(false)
  return <InlineConfirmation open={open} onOpenChange={setOpen} disabled={disabled} onConfirm={confirm}
    triggerLabel="카드 지우기" title="카드를 지울까요?" message="되돌릴 수 없어요." confirmLabel="지우기" />
}

test('같은 영역에 확인을 펼치고 취소에 포커스를 두며 취소하면 원래 버튼으로 돌아간다', async () => {
  render(<Example />)
  const trigger = screen.getByRole('button', { name: '카드 지우기' })
  expect(trigger).toHaveAttribute('aria-expanded', 'false')
  expect(trigger).not.toHaveAttribute('aria-controls')
  fireEvent.click(trigger)
  const panel = screen.getByRole('region', { name: '카드를 지울까요?' })
  expect(trigger).toHaveAttribute('aria-controls', panel.id)
  expect(panel).toHaveTextContent('되돌릴 수 없어요.')
  await waitFor(() => expect(screen.getByRole('button', { name: '취소' })).toHaveFocus())
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(panel).not.toBeInTheDocument()
  expect(trigger).toHaveFocus()
})

test('확인 동작을 전달하고 요청 중에는 펼침·확인·취소를 잠근다', () => {
  const confirm = vi.fn()
  const props = { open: true, onOpenChange: vi.fn(), onConfirm: confirm, triggerLabel: '카드 지우기', title: '카드를 지울까요?', message: '되돌릴 수 없어요.', confirmLabel: '지우기' }
  const { rerender } = render(<InlineConfirmation {...props} />)
  fireEvent.click(screen.getByRole('button', { name: '지우기' }))
  expect(confirm).toHaveBeenCalledTimes(1)
  rerender(<InlineConfirmation {...props} disabled />)
  for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled()
})
