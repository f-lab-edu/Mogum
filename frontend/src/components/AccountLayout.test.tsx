import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import AccountLayout from './AccountLayout'

test('로그인 전 화면 내용을 하나의 본문에 담고 탐색 바를 두지 않는다', () => {
  render(<AccountLayout><h1>계정 화면</h1><p>안내</p></AccountLayout>)

  expect(screen.getAllByRole('main')).toHaveLength(1)
  expect(screen.getByRole('main')).toContainElement(screen.getByRole('heading', { name: '계정 화면' }))
  expect(screen.getByText('안내')).toBeVisible()
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
})
