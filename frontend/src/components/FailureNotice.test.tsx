import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import FailureNotice from './FailureNotice'

test('무엇이 안 됐는지와 전달된 안내·동작을 오류 영역에서 함께 보인다', () => {
  render(<FailureNotice title="버리지 못했어요." message="서버가 정한 문구"><button>다시 시도</button></FailureNotice>)
  const notice = screen.getByRole('alert')
  expect(notice).toHaveTextContent('버리지 못했어요.')
  expect(notice).toHaveTextContent('서버가 정한 문구')
  expect(notice).toContainElement(screen.getByRole('button', { name: '다시 시도' }))
})
