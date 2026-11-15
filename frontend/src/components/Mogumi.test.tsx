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
  expect(mascot).toHaveClass('w-32')
})

test('로고 크기는 같은 사본과 대체 글을 유지하고 40px로 줄인다', () => {
  render(<Mogumi size="logo" />)
  const mascot = screen.getByRole('img', { name: '보라색 머그컵 안에서 책을 읽는 모구미' })
  expect(mascot).toHaveClass('w-10', 'h-auto', 'object-contain')
  expect(mascot).not.toHaveClass('w-32')
  expect(mascot).toHaveAttribute('src', expect.stringMatching(/^\/src\/assets\/mogumi-reading\.png/))
})
