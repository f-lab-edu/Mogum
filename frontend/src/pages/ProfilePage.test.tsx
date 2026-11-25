import { QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import { createAuthQueryClient } from '../api/queryClient'
import type { Me } from '../api/useMe'
import AppLayout from '../components/AppLayout'
import RequireAuth from '../components/RequireAuth'
import LoginPage from './LoginPage'
import ProfilePage from './ProfilePage'

const me: Me = {
  status: 'APPROVED', name: '김민서', email: 'minseo@example.com',
  credit: { balance: 18, nextRefillDate: '2026-12-02' },
}
const response = (data: Me) => new Response(JSON.stringify({ data }))
const clients: ReturnType<typeof createAuthQueryClient>[] = []

function open(data: Me = me) {
  const client = createAuthQueryClient(vi.fn())
  // 신선한 캐시가 있어도 탭 진입 때 서버를 다시 읽어야 한다.
  client.setDefaultOptions({ queries: { staleTime: Infinity }, mutations: { retry: false } })
  client.setQueryData(['me'], data)
  clients.push(client)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(data))))
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/profile']}>
    <Routes>
      <Route element={<RequireAuth />}>
        <Route path="/profile" element={<AppLayout><ProfilePage /></AppLayout>} />
        <Route path="/courses" element={<Link to="/profile">내 정보로</Link>} />
      </Route>
      <Route path="/login" element={<LoginPage />} />
    </Routes>
  </MemoryRouter></QueryClientProvider>)
  return client
}

afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  sessionStorage.clear()
})

test('내 정보는 계정·남은 크레딧·다음 채움 순서로 보이고 제목에 포커스를 둔다', async () => {
  open()
  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: '내 정보' })).toHaveFocus()
    expect(document.title).toBe('내 정보 · 머금')
    expect(screen.getByText(me.name)).toBeVisible()
    expect(screen.getByText(me.email)).toBeVisible()
    expect(screen.getByText('남은 크레딧 18')).toBeVisible()
    expect(screen.getByText('12월 2일에 채워져요')).toBeVisible()
  })
  const identity = screen.getByRole('region', { name: '구글 계정' })
  expect(screen.queryByText('신청 계정')).not.toBeInTheDocument()
  const balance = screen.getByText('남은 크레딧 18')
  const date = screen.getByText('12월 2일에 채워져요')
  expect(identity.compareDocumentPosition(balance) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(balance.compareDocumentPosition(date) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getAllByRole('main')).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '뒤로 가기' })).not.toBeInTheDocument()
})

test.each(['Asia/Seoul', 'America/Los_Angeles', 'Pacific/Honolulu'])('%s 시간대에서도 서버 날짜 2026-12-02를 12월 2일로 보인다', async (timezone) => {
  vi.stubEnv('TZ', timezone)
  open()
  expect(await screen.findByText('12월 2일에 채워져요')).toBeVisible()
})

test('크레딧 칸이 없으면 잔액과 채움 날짜를 모두 숨긴다', async () => {
  open({ status: 'APPROVED', name: me.name, email: me.email })
  await waitFor(() => expect(fetch).toHaveBeenCalled())
  expect(screen.getByText(me.email)).toBeVisible()
  expect(screen.queryByText(/크레딧|채워져요/)).not.toBeInTheDocument()
})

test('처리방침 보기는 privacy 주소를 가리킨다', async () => {
  open()
  expect(await screen.findByRole('link', { name: '처리방침 보기' })).toHaveAttribute('href', '/privacy')
})

test('계정 동작만 두고 스트릭·통계·탈퇴·기기·크레딧 기록이나 종류별 값은 두지 않는다', async () => {
  open()
  await waitFor(() => expect(fetch).toHaveBeenCalled())
  expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual(['로그아웃'])
  expect(screen.queryByText(/스트릭|연속|학습 통계|탈퇴|로그인 기기|원격 로그아웃|크레딧 기록|한 주 몫|지금은 만들 수/)).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '스트릭' })).not.toBeInTheDocument()
})

test('로그아웃 요청 중 버튼을 잠그고 성공한 뒤 나 캐시를 지우고 로그인으로 간다', async () => {
  const client = open()
  await waitFor(() => expect(client.isFetching()).toBe(0))
  let resolve!: (value: Response) => void
  vi.mocked(fetch).mockImplementation(() => new Promise((done) => { resolve = done }))
  const button = screen.getByRole('button', { name: '로그아웃' })
  fireEvent.click(button)
  await waitFor(() => expect(button).toBeDisabled())
  fireEvent.click(button)
  expect(fetch).toHaveBeenLastCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST', credentials: 'include' }))
  expect(vi.mocked(fetch).mock.calls.filter(([path]) => path === '/api/auth/logout')).toHaveLength(1)
  expect(screen.queryByRole('link', { name: 'Google 계정으로 계속하기' })).not.toBeInTheDocument()
  expect(client.getQueryData(['me'])).toEqual(me)
  await act(async () => resolve(new Response(null, { status: 204 })))
  expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
  expect(client.getQueryData(['me'])).toBeUndefined()
})

test.each(['서버', '연결'])('%s 로그아웃 실패는 계정을 유지하고 안내와 다시 시도를 보인다', async (failure) => {
  const client = open()
  await waitFor(() => expect(client.isFetching()).toBe(0))
  if (failure === '서버') vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
    code: 'FUTURE_ERROR', message: '잠시 뒤 다시 로그아웃해 주세요.',
  }), { status: 503 })))
  else vi.mocked(fetch).mockRejectedValue(new TypeError('PRIVATE_NETWORK_DETAILS'))
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃하지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent(failure === '서버'
    ? '잠시 뒤 다시 로그아웃해 주세요.' : '연결하지 못했어요. 다시 시도해 주세요.')
  expect(screen.queryByText(/FUTURE_ERROR|PRIVATE_NETWORK_DETAILS/)).not.toBeInTheDocument()
  expect(client.getQueryData(['me'])).toEqual(me)
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
})

test('탭 진입과 재진입 때 나를 다시 읽어 사용 뒤 바뀐 잔액을 outlet context로 표시한다', async () => {
  open()
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/me', expect.objectContaining({ method: 'GET' })))
  fireEvent.click(screen.getAllByRole('link', { name: '코스' })[0])
  const back = await screen.findByRole('link', { name: '내 정보로' })
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(response({ ...me, credit: { balance: 7, nextRefillDate: '2026-12-09' } })))
  fireEvent.click(back)
  await waitFor(() => {
    expect(screen.getByText('남은 크레딧 7')).toBeVisible()
    expect(screen.getByText('12월 9일에 채워져요')).toBeVisible()
    expect(screen.queryByText('남은 크레딧 18')).not.toBeInTheDocument()
  })
  expect(vi.mocked(fetch).mock.calls.filter(([path]) => path === '/api/me')).toHaveLength(2)
})

test.each(['연결', '서버'])('나를 받은 뒤 내 정보 탭의 %s 재조회가 재시도까지 실패해도 이전 잔액과 화면을 유지한다', async (failure) => {
  const client = open()
  await screen.findByText('남은 크레딧 18')
  await waitFor(() => {
    expect(client.isFetching()).toBe(0)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  fireEvent.click(screen.getAllByRole('link', { name: '코스' })[0])
  const back = await screen.findByRole('link', { name: '내 정보로' })
  if (failure === '연결') vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'))
  else vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
    code: 'INTERNAL_ERROR', message: '서버 내부 문구',
  }), { status: 500 })))

  fireEvent.click(back)
  await waitFor(() => {
    expect(client.getQueryState(['me'])).toMatchObject({ status: 'error', fetchStatus: 'idle' })
    expect(fetch).toHaveBeenCalledTimes(4)
  }, { timeout: 5000 })
  await screen.findByRole('heading', { level: 1, name: '내 정보' })
  await waitFor(() => {
    expect(screen.getByRole('heading', { level: 1, name: '내 정보' })).toBeVisible()
    expect(screen.getByText(me.name)).toBeVisible()
    expect(screen.getByText(me.email)).toBeVisible()
    expect(screen.getByText('남은 크레딧 18')).toBeVisible()
    expect(screen.getByText('12월 2일에 채워져요')).toBeVisible()
    expect(screen.queryByText('불러오지 못했어요.')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
}, 10000)
