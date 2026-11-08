import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import Mogumi from './Mogumi'

test('책 읽는 모구미에 대체 글을 주고 로컬 사본을 불러온다', () => {
  render(<Mogumi />)

  const mascot = screen.getByRole('img', { name: '보라색 머그컵 안에서 책을 읽는 모구미' })
  expect(mascot).toBeVisible()
  expect(mascot).toHaveAttribute('src', expect.stringMatching(/^\/src\/assets\/mogumi-reading\.png/))
  expect(mascot).toHaveAttribute('width', '384')
  expect(mascot).toHaveAttribute('height', '351')
})
