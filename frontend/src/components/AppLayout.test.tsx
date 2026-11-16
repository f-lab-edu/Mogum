import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import { expect, test } from 'vitest'
import AppLayout from './AppLayout'

const tabs = ['홈', '코스', '로드맵', '복습', '내 정보']
const paths = ['/', '/courses', '/roadmaps', '/review', '/profile']
const me = { status: 'APPROVED', name: '민서', email: 'minseo@example.com' }

function open(children: ReactNode, path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Outlet context={me} />}>
          <Route path="*" element={children} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

test.each(paths)('%s에서 모바일·데스크톱 모두 다섯 링크를 같은 순서로 보이고 현재 탭을 표시한다', (path) => {
  open(<AppLayout><h1>자리 화면</h1></AppLayout>, path)

  const navigations = screen.getAllByRole('navigation')
  expect(navigations).toHaveLength(2)
  for (const navigation of navigations) {
    const links = within(navigation).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(tabs)
    expect(links.map((link) => link.getAttribute('href'))).toEqual(paths)
    expect(links.filter((link) => link.getAttribute('aria-current') === 'page')).toEqual([links[paths.indexOf(path)]])
    expect(links[paths.indexOf(path)]).toHaveClass('text-primary', 'font-bold')
    for (const link of links) {
      expect(link).not.toHaveAttribute('target')
      expect(link).toHaveClass('min-h-11', 'focus-visible:outline-2', 'focus-visible:outline-offset-2', 'focus-visible:outline-primary')
    }
  }
})

test('1024px에서 바뀌는 바·사이드바와 본문 아래 여백을 구조·클래스로 확인한다', () => {
  open(<AppLayout><h1>자리 화면</h1></AppLayout>)
  const sidebar = screen.getByRole('complementary')
  expect(sidebar).toHaveClass('hidden', 'lg:flex', 'fixed', 'w-64')
  expect(sidebar).toContainElement(screen.getByRole('navigation', { name: '주요 화면 (데스크톱)' }))
  expect(screen.getByRole('navigation', { name: '주요 화면 (모바일)' })).toHaveClass('fixed', 'bottom-0', 'lg:hidden')
  expect(screen.getByRole('banner')).toHaveClass('h-14')
  expect(screen.getByRole('main')).toHaveClass('pb-24', 'lg:pb-8')
  expect(screen.getByRole('main').parentElement).toHaveClass('lg:ml-64')
  expect(screen.getAllByRole('main')).toHaveLength(1)
})

test('탭 첫 화면의 위 바와 사이드바는 작은 모구미와 머금 로고를 쓴다', () => {
  open(<AppLayout><h1>자리 화면</h1></AppLayout>)
  const header = screen.getByRole('banner')
  const sidebar = screen.getByRole('complementary')
  expect(within(header).getByText('머금').parentElement).toHaveClass('lg:hidden')
  expect(within(sidebar).getByText('머금').parentElement).not.toHaveClass('lg:hidden')
  expect(header).not.toHaveClass('lg:hidden')
  expect(header).toHaveClass('h-14', 'bg-surface', 'border-b', 'border-border')
  for (const region of [screen.getByRole('banner'), screen.getByRole('complementary')]) {
    expect(within(region).getByText('머금')).toBeVisible()
    expect(within(region).getByRole('img', { name: '보라색 머그컵 안에서 책을 읽는 모구미' })).toHaveClass('w-10')
  }
  expect(screen.queryByRole('button', { name: '뒤로 가기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  expect(screen.queryByText(/스트릭|티어|오늘의 목표|AI 미션|버전|크레딧|토큰|모델/)).not.toBeInTheDocument()
})

test('탭 아이콘은 guide의 다섯 인라인 SVG이며 장식으로 처리한다', () => {
  open(<AppLayout><h1>자리 화면</h1></AppLayout>)
  for (const navigation of screen.getAllByRole('navigation')) {
    const icons = within(navigation).getAllByRole('link').map((link) => link.querySelector('svg')!)
    for (const icon of icons) {
      expect(icon).toHaveAttribute('viewBox', '0 0 24 24')
      expect(icon).toHaveAttribute('fill', 'none')
      expect(icon).toHaveAttribute('stroke', 'currentColor')
      expect(icon).toHaveAttribute('stroke-width', '2')
      expect(icon).toHaveAttribute('aria-hidden', 'true')
      expect(icon).toHaveAttribute('focusable', 'false')
    }
    expect(icons[0].querySelector('path')).toHaveAttribute('d', 'M3 10.5L12 3l9 7.5v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9z')
    expect(icons[0].querySelector('polyline')).toHaveAttribute('points', '9 21 9 12 15 12 15 21')
    expect([...icons[1].querySelectorAll('path')].map((node) => node.getAttribute('d'))).toEqual(['M22 10v6M2 10l10-5 10 5-10 5z', 'M6 12v5c3 3 9 3 12 0v-5'])
    expect(icons[2].querySelector('path')).toHaveAttribute('d', 'M9 19h8.5a4.5 4.5 0 0 0 4.5-4.5v-5A4.5 4.5 0 0 0 17.5 5H9')
    expect([...icons[2].querySelectorAll('circle')].map((node) => [node.getAttribute('cx'), node.getAttribute('cy'), node.getAttribute('r')])).toEqual([['6', '19', '3'], ['6', '5', '3']])
    expect(icons[3].querySelector('rect')).toHaveAttribute('rx', '2')
    expect(icons[3].querySelector('path')).toHaveAttribute('d', 'M7 2.5h12a2 2 0 0 1 2 2v13')
    expect(icons[4].querySelector('path')).toHaveAttribute('d', 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2')
    expect(icons[4].querySelector('circle')).toHaveAttribute('r', '4')
  }
})

test('화면이 title을 넘기면 뒤로 가기·제목을 보이고 실제 이전 화면으로 돌아간다', async () => {
  render(
    <MemoryRouter initialEntries={['/courses', '/jobs/12']}>
      <Routes>
        <Route element={<Outlet context={me} />}>
          <Route path="/courses" element={<AppLayout><h1>코스 자리</h1></AppLayout>} />
          <Route path="/jobs/:id" element={<AppLayout title="기다리는 화면"><p>화면 내용</p></AppLayout>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
  const header = screen.getByRole('banner')
  const title = within(header).getByRole('heading', { name: '기다리는 화면' })
  await waitFor(() => {
    expect(title).toHaveFocus()
    expect(document.title).toBe('기다리는 화면 · 머금')
  })
  expect(within(header).queryByRole('img')).not.toBeInTheDocument()
  const back = within(header).getByRole('button', { name: '뒤로 가기' })
  expect(back).toHaveClass('min-h-11', 'min-w-11', 'focus-visible:outline-2', 'focus-visible:outline-offset-2', 'focus-visible:outline-primary')
  back.focus()
  await waitFor(() => expect(back).toHaveFocus())
  fireEvent.click(back)
  expect(await screen.findByRole('heading', { name: '코스 자리' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '뒤로 가기' })).not.toBeInTheDocument()
  expect(within(screen.getByRole('banner')).getByText('머금')).toBeVisible()
})

test('탭 링크에 키보드 포커스를 두고 같은 브라우저 탭에서 이동한다', async () => {
  open(<AppLayout><h1>자리 화면</h1></AppLayout>)
  const link = within(screen.getByRole('navigation', { name: '주요 화면 (모바일)' })).getByRole('link', { name: '코스' })
  expect(link).not.toHaveAttribute('tabindex', '-1')
  link.focus()
  await waitFor(() => expect(link).toHaveFocus())
  fireEvent.click(link)
  await waitFor(() => expect(link).toHaveAttribute('aria-current', 'page'))
})

test('코스에서 들어간 주소도 코스 탭을 현재 위치로 표시한다', () => {
  open(<AppLayout title="코스 화면"><p>내용</p></AppLayout>, '/courses/12')
  for (const navigation of screen.getAllByRole('navigation')) {
    expect(within(navigation).getByRole('link', { name: '코스' })).toHaveAttribute('aria-current', 'page')
    expect(within(navigation).getByRole('link', { name: '홈' })).not.toHaveAttribute('aria-current')
  }
})
