import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../App'
import { openGoogleLogin } from '../lib/authNavigation'

vi.mock('../lib/authNavigation', async (importOriginal) => ({
  ...await importOriginal<typeof import('../lib/authNavigation')>(),
  openGoogleLogin: vi.fn(),
}))

const identity = { name: '김민서', email: 'minseo@example.com' }
const response = (status: string) => new Response(JSON.stringify({ data: { status, ...identity } }))
const serverFailure = () => new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: '지금은 신청을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.' }), { status: 500 })

function open(status = 'NOT_APPLIED', path = '/apply') {
  window.history.replaceState(null, '', path)
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response(status))))
  return render(<App />)
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  sessionStorage.clear()
})

test.each([
  ['NOT_APPLIED', '머금 이용 신청'],
  ['APPLIED', '가입 승인을 기다리고 있어요'],
  ['REJECTED', '이번에는 함께하지 못하게 되었어요'],
])('%s 화면은 계정과 처리방침 입구를 보여 주고 입력·상세 이유·추가 메뉴를 두지 않는다', async (status, heading) => {
  open(status, '/')
  const title = await screen.findByRole('heading', { level: 1, name: heading })
  expect(title).toHaveFocus()
  expect(document.title).toBe(`${heading} · 머금`)
  expect(screen.getByText(identity.email)).toBeVisible()
  expect(screen.getByText(identity.name)).toBeVisible()
  expect(screen.getByRole('img', { name: '보라색 머그컵 안에서 책을 읽는 모구미' })).toBeVisible()
  expect(screen.getByRole('link', { name: '처리방침 보기' })).toHaveAttribute('href', '/privacy')
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  expect(screen.queryByRole('banner')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /새로고침|다시 신청|문의하기|탈퇴/ })).not.toBeInTheDocument()
  expect(screen.queryByText(/거절 이유|신청 일시|프로필 이미지|정원이 가득|토큰|크레딧/)).not.toBeInTheDocument()
  if (status === 'NOT_APPLIED') {
    expect(screen.getByText('신청하면 구글 이메일 · 이름을 저장해요')).toBeVisible()
    expect(screen.getByRole('button', { name: '신청' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '다른 계정으로 로그인' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  } else {
    expect(screen.getByText(/처리방침에 적힌 방법으로 운영자에게 요청해 주세요/)).toBeVisible()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '신청' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다른 계정으로 로그인' })).not.toBeInTheDocument()
    if (status === 'APPLIED') {
      expect(screen.getByText('승인돼도 따로 알리지 않아요. 나중에 다시 들어와 확인해 주세요.')).toBeVisible()
      expect(screen.getByText(/신청을 지우려면/)).toBeVisible()
    } else {
      expect(screen.getByText(/신청 정보\(구글 이메일 · 이름\)가 남아 있어요/)).toBeVisible()
      expect(screen.queryByText(/정원제|때문|사유/)).not.toBeInTheDocument()
    }
  }
})

test('신청은 본문 없이 한 번만 보내고 요청 중 버튼과 계정 전환을 막으며 성공 응답으로 승인 대기로 간다', async () => {
  open()
  const button = await screen.findByRole('button', { name: '신청' })
  let resolve!: (value: Response) => void
  vi.mocked(fetch).mockImplementation(() => new Promise((done) => { resolve = done }))
  fireEvent.click(button)
  await waitFor(() => expect(button).toBeDisabled())
  expect(screen.getByRole('button', { name: '다른 계정으로 로그인' })).toBeDisabled()
  fireEvent.click(button)
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(fetch).toHaveBeenLastCalledWith('/api/account/apply', expect.objectContaining({ method: 'POST', credentials: 'include' }))
  expect(vi.mocked(fetch).mock.calls[1][1]?.body).toBeUndefined()
  await act(async () => resolve(response('APPLIED')))
  expect(await screen.findByRole('heading', { name: '가입 승인을 기다리고 있어요' })).toBeVisible()
  expect(window.location.pathname).toBe('/waiting-approval')
})

test.each(['APPROVED', 'REJECTED'])('신청 성공이 기존 회원의 %s 상태를 반환하면 그 상태를 따른다', async (status) => {
  open()
  const button = await screen.findByRole('button', { name: '신청' })
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(response(status)))
  fireEvent.click(button)
  expect(await screen.findByRole('heading', { name: status === 'APPROVED' ? '머금' : '이번에는 함께하지 못하게 되었어요' })).toBeVisible()
  expect(window.location.pathname).toBe(status === 'APPROVED' ? '/' : '/rejected')
})

test.each(['서버', '연결'])('%s 신청 실패 뒤 서버 문구 또는 연결 안내와 다시 시도를 보인다', async (failure) => {
  open()
  const button = await screen.findByRole('button', { name: '신청' })
  if (failure === '서버') vi.mocked(fetch).mockImplementation(() => Promise.resolve(serverFailure()))
  else vi.mocked(fetch).mockRejectedValue(new TypeError('PRIVATE_NETWORK_DETAILS'))
  fireEvent.click(button)
  const retry = await screen.findByRole('button', { name: '다시 시도' })
  expect(screen.getByRole('alert')).toHaveTextContent('신청하지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent(failure === '서버'
    ? '지금은 신청을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.' : '연결하지 못했어요. 다시 시도해 주세요.')
  expect(screen.queryByText(/INTERNAL_ERROR|PRIVATE_NETWORK_DETAILS/)).not.toBeInTheDocument()
  expect(fetch).toHaveBeenCalledTimes(2)
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(response('APPLIED')))
  fireEvent.click(retry)
  expect(await screen.findByRole('heading', { name: '가입 승인을 기다리고 있어요' })).toBeVisible()
})

test('다른 계정 로그인은 로그아웃 완료를 기다린 뒤 Google 로그인을 연다', async () => {
  open()
  const button = await screen.findByRole('button', { name: '다른 계정으로 로그인' })
  let resolve!: (value: Response) => void
  vi.mocked(fetch).mockImplementation(() => new Promise((done) => { resolve = done }))
  fireEvent.click(button)
  await waitFor(() => expect(button).toBeDisabled())
  expect(fetch).toHaveBeenLastCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST' }))
  expect(openGoogleLogin).not.toHaveBeenCalled()
  await act(async () => resolve(new Response(null, { status: 204 })))
  await waitFor(() => expect(openGoogleLogin).toHaveBeenCalledOnce())
})

test.each(['APPLIED', 'REJECTED'])('%s에서 로그아웃하면 로그인으로 가고 돌아와도 이전 나 캐시를 쓰지 않는다', async (status) => {
  open(status, '/')
  const button = await screen.findByRole('button', { name: '로그아웃' })
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))
  fireEvent.click(button)
  expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
  expect(window.location.pathname).toBe('/login')
  expect(screen.getByRole('status')).toBeEmptyDOMElement()
  expect(fetch).toHaveBeenCalledTimes(2)
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(response('NOT_APPLIED')))
  await act(async () => {
    window.history.pushState(null, '', '/')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  expect(await screen.findByRole('button', { name: '신청' })).toBeVisible()
  expect(fetch).toHaveBeenCalledTimes(3)
})

test.each(['NOT_APPLIED', 'APPLIED'])('%s 로그아웃 실패는 화면에 남아 서버 문구와 재시도를 보인다', async (status) => {
  open(status, '/')
  const label = status === 'NOT_APPLIED' ? '다른 계정으로 로그인' : '로그아웃'
  const button = await screen.findByRole('button', { name: label })
  vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'FUTURE_ERROR', message: '잠시 뒤 다시 로그아웃해 주세요.' }), { status: 503 })))
  fireEvent.click(button)
  expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃하지 못했어요.')
  expect(screen.getByRole('alert')).toHaveTextContent('잠시 뒤 다시 로그아웃해 주세요.')
  expect(openGoogleLogin).not.toHaveBeenCalled()
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  if (status === 'NOT_APPLIED') await waitFor(() => expect(openGoogleLogin).toHaveBeenCalledOnce())
  else expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
})

test('신청 중 401도 공통 로그인 만료 경로로 간다', async () => {
  open()
  const button = await screen.findByRole('button', { name: '신청' })
  vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '다시 로그인해 주세요.' }), { status: 401 }))
  fireEvent.click(button)
  expect(await screen.findByRole('link', { name: 'Google 계정으로 계속하기' })).toBeVisible()
  expect(screen.getByRole('status')).toHaveTextContent('다시 로그인해 주세요.')
})
