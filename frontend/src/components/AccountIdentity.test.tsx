import { render, screen, within } from '@testing-library/react'
import { expect, test } from 'vitest'
import AccountIdentity from './AccountIdentity'

test('계정 영역은 이름과 이메일 전체를 텍스트로 읽을 수 있고 사진을 요청하지 않는다', () => {
  const email = `${'long'.repeat(30)}@example.com`
  render(<AccountIdentity name="김민서" email={email} />)
  const identity = screen.getByRole('region', { name: '신청 계정' })
  expect(within(identity).getByRole('heading', { level: 2, name: '신청 계정' })).toBeVisible()
  expect(within(identity).getByText('김민서')).toBeVisible()
  expect(within(identity).getByText(email)).toBeVisible()
  expect(within(identity).queryByRole('img')).not.toBeInTheDocument()
  expect(within(identity).getByText(email)).not.toHaveClass('truncate')
})

test('전달한 제목은 계정 영역 이름이자 보이는 제목이다', () => {
  render(<AccountIdentity name="김민서" email="minseo@example.com" title="구글 계정" />)
  const identity = screen.getByRole('region', { name: '구글 계정' })
  expect(within(identity).getByRole('heading', { level: 2, name: '구글 계정' })).toBeVisible()
  expect(screen.queryByText('신청 계정')).not.toBeInTheDocument()
})
