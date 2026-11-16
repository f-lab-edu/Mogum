import { render, screen, waitFor } from '@testing-library/react'
import { expect, test } from 'vitest'
import PlaceholderPage from './PlaceholderPage'

test('홈 자리는 머금 제목만 표시하고 본문 틀을 중복하지 않는다', async () => {
  render(<PlaceholderPage />)

  const title = screen.getByRole('heading', { level: 1, name: '머금' })
  expect(title).toBeVisible()
  expect(screen.queryByRole('main')).not.toBeInTheDocument()
  await waitFor(() => {
    expect(title).toHaveFocus()
    expect(document.title).toBe('홈 · 머금')
  })
})

test('다른 탭은 전달한 제목 한 줄만 보이고 이동하면 포커스·문서 제목을 바꾼다', async () => {
  const view = render(<PlaceholderPage title="코스" />)
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '코스' })).toHaveFocus()
    expect(document.title).toBe('코스 · 머금')
  })
  view.rerender(<PlaceholderPage title="내 정보" />)
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '내 정보' })).toHaveFocus()
    expect(document.title).toBe('내 정보 · 머금')
  })
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})
