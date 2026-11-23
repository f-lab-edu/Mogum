import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import CelebrationPage from './CelebrationPage'

const clients: QueryClient[] = []
function open(roadmapId?: number, failed = false) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(failed
    ? { code: 'NOT_FOUND', message: '찾을 수 없습니다' }
    : { data: { id: 31, title: '자바 기초', status: 'COMPLETED', roadmapId } }), { status: failed ? 404 : 200 }))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/', element: <h1>홈 자리</h1> },
      { path: '/courses/:courseId/done', element: <AppLayout title="축하 화면"><CelebrationPage /></AppLayout> },
    ],
  }], { initialEntries: ['/courses/31/done'] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return router
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
})

test.each([undefined, 41])('로드맵 %s와 무관하게 제목이 든 문구 하나·모구미·홈으로만 표시한다', async (roadmapId) => {
  const router = open(roadmapId)
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '자바 기초 코스를 마쳤어요. 고생했어요!' })).toBeVisible()
    expect(within(screen.getByRole('main')).getByRole('img')).toHaveAttribute('src', expect.stringContaining('mogumi-reading'))
    expect(within(screen.getByRole('main')).getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('main')).not.toHaveTextContent(/진도|카드|스트릭|증서|다음 코스|토큰|모델|비용/)
    expect(router.state.location.pathname).toBe('/courses/31/done')
  })
  expect(fetch).toHaveBeenCalledWith('/api/courses/31', expect.objectContaining({ method: 'GET' }))
  fireEvent.click(screen.getByRole('button', { name: '홈으로' }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/'))
})

test('코스를 읽지 못하면 서버 문구와 홈으로를 보인다', async () => {
  open(undefined, true)
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('찾을 수 없습니다')
    expect(screen.queryByText('NOT_FOUND')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '홈으로' })).toBeEnabled()
  })
})
