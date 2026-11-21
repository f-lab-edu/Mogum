import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import ProgressBar from './ProgressBar'

test.each([[0, 5], [2, 5], [5, 5], [0, 0]])('%s / %s 진도를 이름·현재값·전체와 글로 제공한다', (completed, total) => {
  render(<ProgressBar label="학습 진도" completed={completed} total={total} />)
  const bar = screen.getByRole('progressbar', { name: '학습 진도' })
  expect(bar).toHaveAttribute('aria-valuenow', String(completed))
  expect(bar).toHaveAttribute('aria-valuemax', String(total))
  expect(screen.getByText(`${completed} / ${total}`)).toBeVisible()
  expect(bar.firstElementChild).toHaveStyle({ width: `${total === 0 ? 0 : completed / total * 100}%` })
})
