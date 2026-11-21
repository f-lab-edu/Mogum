import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import CoursesPage from './CoursesPage'

const rows = [{ id: 31, title: '자바 기초', status: 'REVIEWING' }, { id: 32, title: '객체 지향', status: 'LEARNING' }, { id: 33, title: '실전 자바', status: 'COMPLETED' }]
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function open(data: unknown = { courses: rows }) {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/courses', element: <AppLayout><CoursesPage /></AppLayout> },
      { path: '/courses/:id', element: <h1>상세 화면 자리</h1> },
    ],
  }], { initialEntries: ['/courses'] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return { client, router }
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
})

test('코스 탭은 서버 순서의 각 행을 계약한 내용으로 보인다', async () => {
  open()
  const list = await screen.findByRole('list', { name: '코스 목록' })
  const items = within(list).getAllByRole('listitem')
  expect(items).toHaveLength(3)
  rows.forEach((row, i) => expect(items[i]).toHaveTextContent(row.title))
  for (const [i, status] of ['검토 중', '학습 중', '다 마침'].entries()) expect(items[i]).toHaveTextContent(status)
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '코스' })).toHaveFocus()
    expect(document.title).toBe('코스 · 머금')
  })
})

test('코스 행을 누르면 같은 탭의 상세 주소로 이동한다', async () => {
  const { router } = open()
  const list = await screen.findByRole('list', { name: '코스 목록' })
  const link = within(within(list).getAllByRole('listitem')[0]).getByRole('link')
  expect(link).toHaveAttribute('href', `/courses/${rows[0].id}`)
  fireEvent.click(link)
  expect(await screen.findByRole('heading', { name: '상세 화면 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe(`/courses/${rows[0].id}`)
})

test('코스가 없으면 안내 한 줄만 보인다', async () => {
  open({ courses: [] })
  expect(await screen.findByText('아직 만든 코스가 없어요')).toBeVisible()
  expect(screen.queryByRole('list', { name: '코스 목록' })).not.toBeInTheDocument()
  expect(within(screen.getByRole('main')).getAllByRole('heading')).toHaveLength(1)
  expect(within(screen.getByRole('main')).queryByRole('link')).not.toBeInTheDocument()
})

test.each([false, true])('빈 목록 %s에서도 새로 배우기·정렬·거르기·진도를 두지 않는다', async (empty) => {
  open({ courses: empty ? [] : rows, credits: 999, model: 'secret-model' })
  if (empty) await screen.findByText('아직 만든 코스가 없어요')
  else await screen.findByRole('list', { name: '코스 목록' })
  expect(screen.getByRole('main')).not.toHaveTextContent(/새로 배우기|새 코스|새 로드맵|정렬|최근 학습|secret-|999|크레딧|토큰|진도율|다음 강의/)
  expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
})

test('목록 연결 실패는 기술 상세 없이 재시도하고 빈 목록으로 오인하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error('secret-error')).mockResolvedValue(response({ courses: rows })))
  open()
  expect(await screen.findByRole('alert')).toHaveTextContent('코스 목록을 불러오지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent('연결하지 못했어요. 다시 시도해 주세요.')
  expect(screen.queryByText('아직 만든 코스가 없어요')).not.toBeInTheDocument()
  expect(screen.getByRole('main')).not.toHaveTextContent('secret-error')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('list', { name: '코스 목록' })).toBeVisible()
})
