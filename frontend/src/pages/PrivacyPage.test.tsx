import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../App'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

test('처리방침 자리는 로그인이나 서버 읽기 없이 제목·준비 중·돌아가기만 표시한다', async () => {
  window.history.replaceState(null, '', '/privacy')
  vi.stubGlobal('fetch', vi.fn())
  render(<App />)
  expect(screen.getByRole('heading', { level: 1, name: '개인정보처리방침' })).toHaveFocus()
  expect(screen.getByText('준비 중이에요.')).toBeVisible()
  expect(screen.getByRole('link', { name: '돌아가기' })).toHaveAttribute('href', '/')
  expect(document.title).toBe('개인정보처리방침 · 머금')
  expect(fetch).not.toHaveBeenCalled()
  expect(screen.queryByRole('article')).not.toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByText(/수집 항목|보관 기간|@|시행일|제3자/)).not.toBeInTheDocument()
})

test.each([
  ['NOT_APPLIED', '/apply', '머금 이용 신청'],
  ['APPLIED', '/waiting-approval', '가입 승인을 기다리고 있어요'],
  ['REJECTED', '/rejected', '이번에는 함께하지 못하게 되었어요'],
])('%s 화면에서 처리방침을 열고 돌아가면 현재 상태의 화면을 읽는다', async (status, path, heading) => {
  window.history.replaceState(null, '', path)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
    data: { status, email: 'minseo@example.com', name: '민서' },
  })))))
  render(<App />)
  fireEvent.click(await screen.findByRole('link', { name: '처리방침 보기' }))
  expect(screen.getByRole('heading', { name: '개인정보처리방침' })).toBeVisible()
  expect(fetch).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('link', { name: '돌아가기' }))
  expect(await screen.findByRole('heading', { name: heading })).toBeVisible()
  expect(window.location.pathname).toBe(path)
  expect(fetch).toHaveBeenCalledTimes(2)
})

test('처리방침에서 돌아올 때 승인이 바뀌었으면 갱신된 상태로 간다', async () => {
  window.history.replaceState(null, '', '/waiting-approval')
  let status = 'APPLIED'
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
    data: { status, email: 'minseo@example.com', name: '민서' },
  })))))
  render(<App />)
  fireEvent.click(await screen.findByRole('link', { name: '처리방침 보기' }))
  status = 'APPROVED'
  fireEvent.click(screen.getByRole('link', { name: '돌아가기' }))
  expect(await screen.findByRole('heading', { name: '머금' })).toBeVisible()
  expect(window.location.pathname).toBe('/')
})
