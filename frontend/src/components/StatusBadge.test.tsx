import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import StatusBadge from './StatusBadge'

test.each(['검토 중', '학습 중', '다 마침', '짜기 전'])('상태 %s를 색에 기대지 않고 글로 표시한다', (label) => {
  render(<StatusBadge>{label}</StatusBadge>)
  expect(screen.getByText(label)).toHaveClass('bg-surface-soft', 'text-primary', 'rounded-control')
})
