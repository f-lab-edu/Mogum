import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import AppLayout from '../components/AppLayout'
import NewLearningPage from './NewLearningPage'

const clients: QueryClient[] = []
const accepted = { id: 42, type: 'NEW_COURSE', status: 'QUEUED', subject: { topic: '자바' } }
function open() {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: accepted }))))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([{
    element: <Outlet context={{ status: 'APPROVED' }} />,
    children: [
      { path: '/', element: <h1>홈 자리</h1> },
      { path: '/new', element: <AppLayout title="새로 배우기"><NewLearningPage /></AppLayout> },
      { path: '/jobs/:jobId', element: <h1>기다리는 화면 자리</h1> },
    ],
  }], { initialEntries: ['/', '/new'] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return router
}
function fill() {
  fireEvent.change(screen.getByRole('textbox', { name: '주제' }), { target: { value: '자바' } })
  fireEvent.click(screen.getByRole('radio', { name: '처음 배워요' }))
  fireEvent.click(screen.getByRole('radio', { name: '업무에 쓰려고요' }))
}
function submit() { fireEvent.click(screen.getByRole('button', { name: '만들기' })) }
function body() { return JSON.parse(vi.mocked(fetch).mock.calls[0][1]!.body as string) }
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
})

test('주제·수준·목적·메모·로드맵·만들기 순서이며 미리 고른 값과 금지 정보가 없다', async () => {
  open()
  const level = screen.getByRole('radiogroup', { name: '지금 수준' })
  const purpose = screen.getByRole('radiogroup', { name: '목적' })
  expect(within(level).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual(['BEGINNER', 'BASICS', 'FAMILIAR', 'OTHER'])
  expect(within(purpose).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual(['WORK', 'INTERVIEW', 'GENERAL', 'OTHER'])
  expect(screen.getAllByRole('radio').every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
  expect(screen.getByRole('checkbox', { name: '로드맵으로 만들기' })).not.toBeChecked()
  expect(screen.getByRole('textbox', { name: '주제' })).toBeRequired()
  expect(screen.getByRole('textbox', { name: '메모' })).not.toBeRequired()
  expect(screen.getByRole('button', { name: '만들기' })).toBeDisabled()
  const order = [screen.getByRole('textbox', { name: '주제' }), level, purpose, screen.getByRole('textbox', { name: '메모' }), screen.getByRole('checkbox'), screen.getByRole('button', { name: '만들기' })]
  for (let index = 1; index < order.length; index++) {
    expect(order[index - 1].compareDocumentPosition(order[index]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  }
  expect(screen.getByRole('main')).not.toHaveTextContent(/크레딧|토큰|gpt|추천 키워드|자격증|주당|강사/)
  expect(screen.queryByRole('textbox', { name: '기타 수준' })).not.toBeInTheDocument()
  expect(screen.queryByRole('textbox', { name: '기타 목적' })).not.toBeInTheDocument()
  expect(screen.getByText(/한 코스를 마치면 그때까지의 복습 결과/)).toBeVisible()
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: '새로 배우기' })).toHaveFocus()
    expect(document.title).toBe('새로 배우기 · 머금')
  })
})

test.each([false, true])('로드맵 체크 %s에 따라 종류를 보내고 작업 주소로 간다', async (roadmap) => {
  const router = open()
  fill()
  if (roadmap) fireEvent.click(screen.getByRole('checkbox'))
  submit()
  expect(await screen.findByRole('heading', { name: '기다리는 화면 자리' })).toBeVisible()
  expect(body()).toEqual({ type: roadmap ? 'NEW_ROADMAP' : 'NEW_COURSE', topic: '자바', level: 'BEGINNER', purpose: 'WORK' })
  expect(router.state.location.pathname).toBe('/jobs/42')
})

test('기타는 선택했을 때만 열리고 필수이며 다른 보기를 고르면 닫히고 보내지 않는다', async () => {
  open()
  fill()
  for (const [group, label, value] of [['지금 수준', '기타 수준', '직접 쓴 수준'], ['목적', '기타 목적', '직접 쓴 목적']]) {
    fireEvent.click(within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name: '기타' }))
    const input = screen.getByRole('textbox', { name: label })
    expect(input).toBeRequired()
    expect(screen.getByRole('button', { name: '만들기' })).toBeDisabled()
    fireEvent.change(input, { target: { value } })
  }
  fireEvent.click(screen.getByRole('radio', { name: '기초는 알아요' }))
  fireEvent.click(screen.getByRole('radio', { name: '면접 준비' }))
  expect(screen.queryByRole('textbox', { name: '기타 수준' })).not.toBeInTheDocument()
  expect(screen.queryByRole('textbox', { name: '기타 목적' })).not.toBeInTheDocument()
  submit()
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(body()).toEqual({ type: 'NEW_COURSE', topic: '자바', level: 'BASICS', purpose: 'INTERVIEW' })
})

test('기타 글과 메모는 쓰는 칸만 평평한 JSON에 담는다', async () => {
  open()
  fill()
  fireEvent.click(within(screen.getByRole('radiogroup', { name: '지금 수준' })).getByRole('radio', { name: '기타' }))
  fireEvent.click(within(screen.getByRole('radiogroup', { name: '목적' })).getByRole('radio', { name: '기타' }))
  fireEvent.change(screen.getByRole('textbox', { name: '기타 수준' }), { target: { value: '경험 있음' } })
  fireEvent.change(screen.getByRole('textbox', { name: '기타 목적' }), { target: { value: '취미' } })
  fireEvent.change(screen.getByRole('textbox', { name: '메모' }), { target: { value: '예제 위주' } })
  submit()
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(body()).toEqual({ type: 'NEW_COURSE', topic: '자바', level: 'OTHER', levelOther: '경험 있음', purpose: 'OTHER', purposeOther: '취미', memo: '예제 위주' })
})

test('공백뿐인 메모는 보내지 않고 체크를 다시 끄면 단일 코스로 보낸다', async () => {
  open()
  fill()
  fireEvent.change(screen.getByRole('textbox', { name: '메모' }), { target: { value: '   ' } })
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('checkbox'))
  submit()
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(body()).toEqual({ type: 'NEW_COURSE', topic: '자바', level: 'BEGINNER', purpose: 'WORK' })
})

test('요청 중 버튼과 입력을 막고 같은 순간의 두 제출도 요청은 한 번이다', async () => {
  let finish!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise<Response>((resolve) => { finish = resolve })))
  open()
  fill()
  const form = screen.getByRole('button', { name: '만들기' }).closest('form')!
  fireEvent.submit(form)
  fireEvent.submit(form)
  await waitFor(() => {
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '만들기' })).toBeDisabled()
  })
  submit()
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('textbox', { name: '주제' })).toBeDisabled()
  await act(async () => { finish(new Response(JSON.stringify({ data: accepted }))) })
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
})

test.each([
  ['주제', 100], ['기타 수준', 50], ['기타 목적', 50], ['메모', 500],
] as const)('%s 상한 %s는 코드 포인트로 세고 넘으면 보내지 않는다', (label, limit) => {
  open()
  fill()
  if (label === '기타 수준' || label === '기타 목적') {
    fireEvent.click(within(screen.getByRole('radiogroup', { name: label === '기타 수준' ? '지금 수준' : '목적' })).getByRole('radio', { name: '기타' }))
  }
  const input = screen.getByRole('textbox', { name: label })
  fireEvent.change(input, { target: { value: '😀'.repeat(limit) } })
  expect(screen.getByRole('button', { name: '만들기' })).toBeEnabled()
  fireEvent.change(input, { target: { value: '😀'.repeat(limit + 1) } })
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByRole('button', { name: '만들기' })).toBeDisabled()
  fireEvent.submit(input.closest('form')!)
  expect(fetch).not.toHaveBeenCalled()
})

test.each([
  ['topic', '주제', 'REQUIRED', '필수 입력이에요.'],
  ['levelOther', '기타 수준', 'TOO_LONG', '글자 수 상한을 넘었어요.'],
  ['purposeOther', '기타 목적', 'NOT_ALLOWED', '이 칸은 비워 주세요.'],
  ['memo', '메모', 'TOO_LONG', '글자 수 상한을 넘었어요.'],
  ['level', '지금 수준', 'INVALID_VALUE', '보기에서 골라 주세요.'],
  ['purpose', '목적', 'REQUIRED', '필수 입력이에요.'],
])('서버 칸 오류 %s·%s는 해당 입력에 연결하고 입력을 보존한다', async (field, label, reason, message) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'FIELD_INVALID', message: '서버의 칸 확인 문구', fields: [{ field, reason }] }), { status: 400 }))))
  open()
  fill()
  if (field.endsWith('Other')) {
    fireEvent.click(within(screen.getByRole('radiogroup', { name: field === 'levelOther' ? '지금 수준' : '목적' })).getByRole('radio', { name: '기타' }))
    fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value: '직접 쓴 값' } })
  }
  submit()
  expect(await screen.findByText(message)).toBeVisible()
  const input = screen.getByRole(field === 'level' || field === 'purpose' ? 'radiogroup' : 'textbox', { name: label })
  expect(input).toHaveAccessibleDescription(new RegExp(message))
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByRole('textbox', { name: '주제' })).toHaveValue('자바')
  expect(screen.getByText('서버의 칸 확인 문구')).toBeVisible()
  expect(screen.getByRole('button', { name: '만들기' })).toBeEnabled()
  expect(screen.queryByText(reason)).not.toBeInTheDocument()
})

test.each([
  ['CREDIT_INSUFFICIENT', '크레딧이 부족합니다. 2026-12-18에 다시 채워집니다'],
  ['TOO_MANY_IN_PROGRESS', '지금 만드는 것이 끝나면 다시 시도해 주세요'],
])('%s는 공통 안내·서버 문구와 입력을 보존하며 다시 누르라는 안내가 없다', async (code, message) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code, message }), { status: 409 }))))
  open()
  fill()
  fireEvent.change(screen.getByRole('textbox', { name: '메모' }), { target: { value: '남길 메모' } })
  fireEvent.click(screen.getByRole('checkbox'))
  submit()
  await screen.findByText('지금은 만들 수 없어요')
  await waitFor(() => {
    expect(screen.getByRole('status')).toHaveTextContent(message)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/안내를 확인한 뒤 만들기를 다시/)).not.toBeInTheDocument()
    const link = screen.queryByRole('link', { name: '남은 크레딧 보기' })
    if (code === 'CREDIT_INSUFFICIENT') expect(link).toHaveAttribute('href', '/profile')
    else expect(link).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '주제' })).toHaveValue('자바')
    expect(screen.getByRole('textbox', { name: '메모' })).toHaveValue('남길 메모')
    expect(screen.getByRole('radio', { name: '처음 배워요' })).toBeChecked()
    expect(screen.getByRole('checkbox')).toBeChecked()
    expect(screen.getByRole('button', { name: '만들기' })).toBeEnabled()
    expect(screen.queryByText(code)).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})

test('모르는 오류는 기존 실패 안내와 다시 제출을 제공한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'FUTURE_CODE', message: '서버가 보내는 새로운 안내' }), { status: 409 }))))
  open()
  fill()
  submit()
  await screen.findByRole('alert')
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('만들기를 요청하지 못했어요.')
    expect(screen.getByRole('alert')).toHaveTextContent('서버가 보내는 새로운 안내')
    expect(screen.getByText('안내를 확인한 뒤 만들기를 다시 눌러 주세요.')).toBeVisible()
    expect(screen.queryByText('지금은 만들 수 없어요')).not.toBeInTheDocument()
    expect(screen.queryByText('FUTURE_CODE')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '만들기' })).toBeEnabled()
  })
  submit()
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
})

test('연결 실패 원문은 숨기고 공통 안내와 다시 제출을 제공한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error('secret-network-detail')).mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data: accepted })))))
  open()
  fill()
  submit()
  await screen.findByRole('alert')
  await waitFor(() => {
    expect(screen.getByRole('alert')).toHaveTextContent('만들기를 요청하지 못했어요.')
    expect(screen.getByRole('alert')).toHaveTextContent('연결하지 못했어요. 다시 시도해 주세요.')
    expect(screen.getByText('안내를 확인한 뒤 만들기를 다시 눌러 주세요.')).toBeVisible()
    expect(screen.queryByText('지금은 만들 수 없어요')).not.toBeInTheDocument()
    expect(screen.queryByText('secret-network-detail')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '만들기' })).toBeEnabled()
  })
  submit()
  await screen.findByRole('heading', { name: '기다리는 화면 자리' })
  expect(fetch).toHaveBeenCalledTimes(2)
})

test('뒤로 가기는 홈으로 돌아간다', async () => {
  const router = open()
  fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/'))
})
