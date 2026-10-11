import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import PlaceholderPage from './PlaceholderPage'

test('자리표시 페이지에 머금 제목을 표시한다', () => {
  render(<PlaceholderPage />)

  expect(screen.getByRole('main')).toContainElement(
    screen.getByRole('heading', { level: 1, name: '머금' }),
  )
})
