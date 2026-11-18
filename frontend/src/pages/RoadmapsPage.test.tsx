import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import RoadmapsPage from './RoadmapsPage'

const rows = [{ id: 41, title: '자바 로드맵', description: '기초부터 실무까지', status: 'REVIEWING', completedCourses: 0, totalCourses: 3 }, { id: 42, title: '웹 로드맵', description: '웹을 만들어요', status: 'IN_PROGRESS', completedCourses: 1, totalCourses: 4 }, { id: 43, title: '파이썬 로드맵', description: '데이터를 다뤄요', status: 'COMPLETED', completedCourses: 2, totalCourses: 2 }]
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function open(data: unknown = { roadmaps: rows }) {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/roadmaps', element: <AppLayout><RoadmapsPage /></AppLayout> },
      { path: '/roadmaps/:id', element: <h1>상세 화면 자리</h1> },
    ],
  }], { initialEntries: ['/roadmaps'] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return { client, router }
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
})

test('로드맵 탭은 서버 순서의 각 행을 계약한 내용으로 보인다', async () => {
  open()
  const list = await screen.findByRole('list', { name: '로드맵 목록' })
  const items = within(list).getAllByRole('listitem')
  expect(items).toHaveLength(3)
  rows.forEach((row, i) => expect(items[i]).toHaveTextContent(row.title))
  rows.forEach((row, i) => expect(items[i]).toHaveTextContent(row.description))
  expect(items[0]).toHaveTextContent('코스 3개 중 0개 마침')
  expect(items[1]).toHaveTextContent('코스 4개 중 1개 마침')
  expect(items[2]).toHaveTextContent('다 마침')
  expect(items[2]).not.toHaveTextContent('2개 중 2개')
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '로드맵' })).toHaveFocus()
    expect(document.title).toBe('로드맵 · 머금')
  })
})

test('로드맵 행을 누르면 같은 탭의 상세 주소로 이동한다', async () => {
  const { router } = open()
  const list = await screen.findByRole('list', { name: '로드맵 목록' })
  const link = within(within(list).getAllByRole('listitem')[0]).getByRole('link')
  expect(link).toHaveAttribute('href', `/roadmaps/${rows[0].id}`)
  fireEvent.click(link)
  expect(await screen.findByRole('heading', { name: '상세 화면 자리' })).toBeVisible()
  expect(router.state.location.pathname).toBe(`/roadmaps/${rows[0].id}`)
})

test('로드맵이 없으면 안내 한 줄만 보인다', async () => {
  open({ roadmaps: [] })
  expect(await screen.findByText('아직 만든 로드맵이 없어요')).toBeVisible()
  expect(screen.queryByRole('list', { name: '로드맵 목록' })).not.toBeInTheDocument()
  expect(within(screen.getByRole('main')).getAllByRole('heading')).toHaveLength(1)
  expect(within(screen.getByRole('main')).queryByRole('link')).not.toBeInTheDocument()
})

test.each([false, true])('빈 목록 %s에서도 새로 배우기·정렬·거르기·진도를 두지 않는다', async (empty) => {
  open({ roadmaps: empty ? [] : rows, credits: 999, model: 'secret-model' })
  if (empty) await screen.findByText('아직 만든 로드맵이 없어요')
  else await screen.findByRole('list', { name: '로드맵 목록' })
  expect(screen.getByRole('main')).not.toHaveTextContent(/새로 배우기|새 코스|새 로드맵|정렬|최근 학습|secret-|999|크레딧|토큰|진도율|다음 강의/)
  expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
})

test('목록 연결 실패는 기술 상세 없이 재시도하고 빈 목록으로 오인하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error('secret-error')).mockResolvedValue(response({ roadmaps: rows })))
  open()
  expect(await screen.findByRole('alert')).toHaveTextContent('로드맵 목록을 불러오지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent('연결하지 못했어요. 다시 시도해 주세요.')
  expect(screen.queryByText('아직 만든 로드맵이 없어요')).not.toBeInTheDocument()
  expect(screen.getByRole('main')).not.toHaveTextContent('secret-error')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('list', { name: '로드맵 목록' })).toBeVisible()
})
