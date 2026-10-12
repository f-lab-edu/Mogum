import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import App from './App'

test('/에서 실제 앱의 라우터와 QueryClientProvider를 거쳐 머금 제목을 표시한다', () => {
  window.history.replaceState(null, '', '/')

  render(<App />)

  expect(screen.getByRole('heading', { level: 1, name: '머금' })).toBeVisible()
})
