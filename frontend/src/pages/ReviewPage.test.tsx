import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'
import ReviewPage from './ReviewPage'

const first = { id: 1, question: '변수란 무엇인가요?', answer: '값을 담는 공간이에요.', nextIntervalsSeconds: { AGAIN: 60, HARD: 600, GOOD: 259200, EASY: 2592000 } }
const second = { ...first, id: 2, question: '상수란 무엇인가요?', answer: '바뀌지 않는 값이에요.' }
const clients: QueryClient[] = []
function response(cards: typeof first[] = [first, second], courseTitle?: string) {
  return new Response(JSON.stringify({ data: { cards, remainingCount: cards.length, courseTitle } }))
}
function failure(code: string, message: string, status = 409) { return new Response(JSON.stringify({ code, message }), { status }) }
function ReviewRoute() {
  const location = useLocation()
  return <ReviewPage key={location.pathname + location.search} />
}
function open(entry = '/review') {
  if (!vi.isMockFunction(fetch)) vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(response())))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter([
    { path: '/review', element: <ReviewRoute /> },
    { path: '/', element: <h1>홈 자리</h1> },
  ], { initialEntries: [entry] })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return { router, client }
}
function reveal() { fireEvent.click(screen.getByRole('button', { name: '답 보기' })) }
function rate(label = '좋음') { fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${label} `) })) }
function writes() { return vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method !== 'GET') }
afterEach(() => { clients.forEach((client) => client.clear()); clients.length = 0; vi.unstubAllGlobals() })

test('첫 질문·남은 수를 보이고 답을 펼친 뒤 네 평가에 짧은 간격을 표시한다', async () => {
  open()
  expect(await screen.findByText(first.question)).toBeVisible()
  expect(screen.getByText('2장 남음')).toBeVisible()
  expect(screen.queryByText(first.answer)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /^좋음/ })).not.toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('heading', { name: '복습' })).toHaveFocus())
  expect(document.title).toBe('복습 · 머금')
  reveal()
  expect(screen.getByText(first.answer)).toBeVisible()
  for (const label of ['다시 1분', '어려움 10분', '좋음 3일', '쉬움 1달']) expect(screen.getByRole('button', { name: label })).toBeEnabled()
  expect(screen.getByRole('group', { name: '스스로 평가하기' })).toHaveClass('grid-cols-2', 'sm:grid-cols-4')
  expect(screen.getByRole('button', { name: '다시 1분' })).toHaveClass('min-h-13')
})

test.each([['다시', 'AGAIN'], ['어려움', 'HARD'], ['좋음', 'GOOD'], ['쉬움', 'EASY']])('%s 평가를 보내면 답을 닫고 다음 카드·줄어든 수를 보인다', async (label, rating) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET' ? response() : new Response(null, { status: 204 }))))
  const { client } = open()
  client.setQueryData(['courses', '31'], { todayReviewCount: 2 })
  await screen.findByText(first.question)
  reveal(); rate(label)
  expect(await screen.findByText(second.question)).toBeVisible()
  expect(screen.getByText('1장 남음')).toBeVisible()
  expect(screen.queryByText(second.answer)).not.toBeInTheDocument()
  expect(fetch).toHaveBeenCalledWith('/api/cards/1/reviews', expect.objectContaining({ method: 'POST', body: JSON.stringify({ rating }) }))
  expect(new Headers(writes()[0][1]?.headers).get('Content-Type')).toBe('application/json')
  expect(client.getQueryState(['courses', '31'])?.isInvalidated).toBe(true)
})

test.each([true, false])('목록 끝에서 다시 읽고 돌아온 카드 유무 %s에 맞게 이어 보이거나 빈 안내를 보인다', async (returned) => {
  let reads = 0
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET'
    ? response(++reads === 1 ? [first] : returned ? [first, second] : []) : new Response(null, { status: 204 }))))
  open()
  await screen.findByText(first.question)
  reveal(); rate('다시')
  await waitFor(() => {
    expect(reads).toBe(2)
    if (returned) {
      expect(screen.getByText(first.question)).toBeVisible()
      expect(screen.getByText('2장 남음')).toBeVisible()
      expect(screen.queryByText(first.answer)).not.toBeInTheDocument()
    } else {
      expect(screen.getByText('오늘 복습할 카드가 없어요')).toBeVisible()
      expect(screen.getByRole('img', { name: '보라색 머그컵 안에서 책을 읽는 모구미' })).toBeVisible()
    }
  })
})

test.each(['review', 'delete'])('%s 요청 중 평가·지우기·확인 동작을 잠그고 같은 순간 두 번 눌러도 한 번만 보낸다', async (type) => {
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? Promise.resolve(response()) : new Promise<Response>((resolve) => { finish = resolve })))
  open()
  await screen.findByText(first.question)
  reveal()
  if (type === 'delete') fireEvent.click(screen.getByRole('button', { name: '카드 지우기' }))
  const button = screen.getByRole('button', { name: type === 'review' ? '좋음 3일' : '지우기' })
  act(() => { button.click(); button.click() })
  await waitFor(() => expect(writes()).toHaveLength(1))
  for (const label of ['다시 1분', '어려움 10분', '좋음 3일', '쉬움 1달', '카드 지우기']) expect(screen.getByRole('button', { name: label })).toBeDisabled()
  if (type === 'delete') expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  await act(async () => { finish(new Response(null, { status: 204 })) })
  expect(await screen.findByText(second.question)).toBeVisible()
})

test.each(['review', 'delete'])('%s 실패는 서버 문구와 현재 질문·답을 보존하고 재시도할 수 있다', async (type) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET' ? response() : failure('INTERNAL_ERROR', '저장하지 못했습니다. 잠시 후 다시 눌러 주세요.', 500))))
  open()
  await screen.findByText(first.question)
  reveal()
  if (type === 'delete') {
    fireEvent.click(screen.getByRole('button', { name: '카드 지우기' }))
    fireEvent.click(screen.getByRole('button', { name: '지우기' }))
  } else rate()
  expect(await screen.findByText('저장하지 못했습니다. 잠시 후 다시 눌러 주세요.')).toBeVisible()
  expect(screen.getByText(first.question)).toBeVisible()
  expect(screen.getByText(first.answer)).toBeVisible()
  expect(screen.getByText('2장 남음')).toBeVisible()
  expect(screen.queryByText('INTERNAL_ERROR')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: type === 'review' ? '좋음 3일' : '지우기' })).toBeEnabled()
  expect(writes()).toHaveLength(1)
})

test.each(['CARD_REVIEW_NOT_ALLOWED', 'NOT_FOUND'])('%s 평가 거절은 문구를 남기고 목록을 다시 읽는다', async (code) => {
  let reads = 0
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET'
    ? response(++reads === 1 ? [first] : [second]) : failure(code, '목록을 다시 확인해 주세요'))))
  open()
  await screen.findByText(first.question)
  reveal(); rate()
  expect(await screen.findByText(second.question)).toBeVisible()
  expect(screen.getByText('목록을 다시 확인해 주세요')).toBeVisible()
  expect(screen.queryByText(code)).not.toBeInTheDocument()
  expect(screen.queryByText(first.question)).not.toBeInTheDocument()
  expect(reads).toBe(2)
})

test('지우기 확인에 삭제 범위를 보이고 취소하면 카드·답을 보존하며 확인하면 본문 없이 지운다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET' ? response() : new Response(null, { status: 204 }))))
  open()
  await screen.findByText(first.question)
  reveal()
  const trigger = screen.getByRole('button', { name: '카드 지우기' })
  fireEvent.click(trigger)
  expect(screen.getByRole('region', { name: '카드를 지울까요?' })).toHaveTextContent('이 카드와 이 카드의 복습 기록이 사라져요. 되돌릴 수 없어요. 카드에 연결된 개념은 남아요.')
  await waitFor(() => expect(screen.getByRole('button', { name: '취소' })).toHaveFocus())
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(trigger).toHaveFocus()
  expect(screen.getByText(first.answer)).toBeVisible()
  expect(writes()).toHaveLength(0)
  fireEvent.click(trigger)
  fireEvent.click(screen.getByRole('button', { name: '지우기' }))
  expect(await screen.findByText(second.question)).toBeVisible()
  expect(screen.getByText('1장 남음')).toBeVisible()
  expect(fetch).toHaveBeenCalledWith('/api/cards/1', expect.objectContaining({ method: 'DELETE' }))
  expect(writes()[0][1]?.body).toBeUndefined()
})

test.each([true, false])('코스로 거르면 쿼리·제목을 쓰고 처음부터 빈 경우 %s도 전체 복습 길을 둔다', async (empty) => {
  let reads = 0
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => Promise.resolve(options.method === 'GET'
    ? response(++reads === 1 && !empty ? [first] : [], '자바 기초') : new Response(null, { status: 204 }))))
  const { router } = open('/review?courseId=31')
  expect(await screen.findByText('자바 기초')).toBeVisible()
  expect(fetch).toHaveBeenCalledWith('/api/reviews/today?courseId=31', expect.objectContaining({ method: 'GET' }))
  if (!empty) { reveal(); rate() }
  expect(await screen.findByText('이 코스는 오늘 복습할 카드가 없어요')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '전체 복습하기' }))
  await waitFor(() => expect(router.state.location.search).toBe(''))
  expect(fetch).toHaveBeenCalledWith('/api/reviews/today', expect.objectContaining({ method: 'GET' }))
})

test.each(['NOT_FOUND', 'INTERNAL_ERROR'])('코스 읽기 %s 실패에는 서버 문구·재시도·전체 복습 길이 있다', async (code) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(failure(code, '찾을 수 없습니다', 404))))
  const { router } = open('/review?courseId=31')
  expect(await screen.findByRole('alert')).toHaveTextContent('복습할 카드를 불러오지 못했어요.')
  expect(screen.getByText('찾을 수 없습니다')).toBeVisible()
  expect(screen.queryByText(code)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '전체 복습하기' }))
  await waitFor(() => expect(router.state.location.search).toBe(''))
})

test('읽기 연결 실패는 공통 문구로 알리고 다시 시도하면 읽는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error('secret-network')).mockImplementation(() => Promise.resolve(response())))
  open()
  expect(await screen.findByText('연결하지 못했어요. 다시 시도해 주세요.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByText(first.question)).toBeVisible()
  expect(screen.queryByText('secret-network')).not.toBeInTheDocument()
})

test('떠났다 돌아올 때 옛 목록·펼친 답을 먼저 보이지 않고 새 응답을 기다린다', async () => {
  let readAgain!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response()).mockImplementation(() => new Promise<Response>((resolve) => { readAgain = resolve })))
  const { router } = open()
  await screen.findByText(first.question)
  reveal()
  await act(async () => { await router.navigate('/') })
  await act(async () => { await router.navigate('/review') })
  expect(screen.getByRole('status')).toHaveTextContent('복습할 카드를 불러오고 있어요.')
  expect(screen.queryByText(first.question)).not.toBeInTheDocument()
  expect(screen.queryByText(first.answer)).not.toBeInTheDocument()
  await act(async () => { readAgain(response([second])) })
  expect(await screen.findByText(second.question)).toBeVisible()
  expect(screen.queryByText(second.answer)).not.toBeInTheDocument()
})

test('응답의 확장 칸·시스템 정보·카드 출처·금지 동작을 표시하지 않는다', async () => {
  const data = { cards: [{ ...first, fsrsState: 'SECRET_STATE', courseTitle: 'SECRET_SOURCE' }], remainingCount: 1, model: 'SECRET_MODEL' }
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ data })))))
  open()
  await screen.findByText(first.question)
  reveal()
  expect(document.body).not.toHaveTextContent(/SECRET_|259200|FSRS|예정 간격|단축키|Space|스트릭|통계|포인트|04:00|오늘 완료|다른 코스 학습|새로 배우기|핵심 포인트|숙련도/)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})
