import { render, screen, within } from '@testing-library/react'
import { expect, test } from 'vitest'
import AccountIdentity from './AccountIdentity'

test('계정 영역은 이름과 이메일 전체를 텍스트로 읽을 수 있고 사진을 요청하지 않는다', () => {
  const email = `${'long'.repeat(30)}@example.com`
  render(<AccountIdentity name="김민서" email={email} />)
  const identity = screen.getByRole('region', { name: '신청 계정' })
  expect(within(identity).getByText('김민서')).toBeVisible()
  expect(within(identity).getByText(email)).toBeVisible()
  expect(within(identity).queryByRole('img')).not.toBeInTheDocument()
  expect(within(identity).getByText(email)).not.toHaveClass('truncate')
})
