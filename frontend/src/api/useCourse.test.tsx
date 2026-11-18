import { useRoadmap } from './useRoadmap'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { coursesKey, useCourses, courseKey, readCourse, startCourse, updateCourseTitle, useCourse, useCourseAction } from './useCourse'

const course = { id: 31, title: '자바', status: 'REVIEWING', lessons: [], progress: { completedLessons: 0, totalLessons: 0 } }
const clients: QueryClient[] = []
function response(data: unknown) { return new Response(JSON.stringify({ data })) }
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}
afterEach(() => {
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

test('코스 읽기는 쿠키·중단 신호를 보내고 주소를 인코딩한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(course)))
  const signal = new AbortController().signal
  expect(await readCourse('31/a', signal)).toEqual(course)
  expect(fetch).toHaveBeenCalledWith('/api/courses/31%2Fa', expect.objectContaining({ method: 'GET', credentials: 'include', signal }))
})

test.each(['start', 'title'] as const)('%s는 공통 CSRF·쿠키 경로와 계약한 본문을 사용한다', async (action) => {
  document.cookie = 'XSRF-TOKEN=course-csrf; path=/'
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(course)))
  expect(await (action === 'start' ? startCourse('31') : updateCourseTitle('31', '새 이름'))).toEqual(course)
  const [path, options] = vi.mocked(fetch).mock.calls[0]
  expect(path).toBe(action === 'start' ? '/api/courses/31/start' : '/api/courses/31')
  expect(options).toMatchObject({ method: action === 'start' ? 'POST' : 'PATCH', credentials: 'include' })
  expect(new Headers(options!.headers).get('X-XSRF-TOKEN')).toBe('course-csrf')
  expect(options!.body).toBe(action === 'start' ? undefined : JSON.stringify({ title: '새 이름' }))
  if (action === 'title') expect(new Headers(options!.headers).get('Content-Type')).toBe('application/json')
})

test('코스 읽기를 TanStack Query에 캐시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(course)))
  const { client, wrapper } = setup()
  const { result } = renderHook(() => useCourse('31'), { wrapper })
  await waitFor(() => expect(result.current.data).toEqual(course))
  expect(client.getQueryData(courseKey('31'))).toEqual(course)
})

test.each([{ type: 'start' as const }, { type: 'title' as const, title: '새 이름' }])('$type 성공은 같은 코스 캐시를 응답 전체로 바꾼다', async (action) => {
  const updated = { ...course, title: '서버 제목', status: 'LEARNING' }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(updated)))
  const { client, wrapper } = setup()
  client.setQueryData(courseKey('31'), course)
  const { result } = renderHook(() => useCourseAction('31'), { wrapper })
  await act(async () => { await result.current.mutateAsync(action) })
  expect(client.getQueryData(courseKey('31'))).toEqual(updated)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('쓰기 실패를 자동 재시도하지 않고 원래 코스 캐시를 보존한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'FIELD_INVALID', message: '입력을 확인해 주십시오' }), { status: 400 })))
  const { client, wrapper } = setup()
  client.setQueryData(courseKey('31'), course)
  const { result } = renderHook(() => useCourseAction('31'), { wrapper })
  await act(async () => { await expect(result.current.mutateAsync({ type: 'title', title: '' })).rejects.toThrow('입력을 확인해 주십시오') })
  expect(client.getQueryData(courseKey('31'))).toEqual(course)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('코스 목록은 쿠키·중단 신호로 읽고 별도 목록 키에 캐시한다', async () => {
  const data = { courses: [course] }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(data)))
  const { client, wrapper } = setup()
  const { result } = renderHook(() => useCourses(), { wrapper })
  await waitFor(() => expect(result.current.data).toEqual(data))
  expect(client.getQueryData(coursesKey)).toEqual(data)
  expect(fetch).toHaveBeenCalledWith('/api/courses', expect.objectContaining({ method: 'GET', credentials: 'include', signal: expect.any(AbortSignal) }))
})

test.each(['start', 'title'] as const)('%s 성공은 목록과 소속 로드맵 캐시도 맞추고 다른 행을 보존한다', async (type) => {
  const updated = { ...course, title: '고친 이름', status: 'LEARNING', roadmapId: 41 }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(updated)))
  const { client, wrapper } = setup()
  const other = { id: 32, title: '다른 코스', status: 'REVIEWING' }
  const planCourse = { id: 31, title: '자바', status: 'REVIEWING', position: 1, description: '설명', concepts: [], progress: 'GENERATED' }
  const roadmap = { id: 41, title: '로드맵', status: 'IN_PROGRESS', description: '로드맵 설명', courses: [planCourse, other] }
  client.setQueryData(coursesKey, { courses: [course, other] })
  client.setQueryData(['roadmaps', '41'], roadmap)
  client.setQueryData(['roadmaps', '42'], { ...roadmap, id: 42 })
  const { result } = renderHook(() => useCourseAction('31'), { wrapper })
  await act(async () => { await result.current.mutateAsync(type === 'start' ? { type } : { type, title: updated.title }) })
  expect(client.getQueryData(coursesKey)).toEqual({ courses: [{ ...course, title: updated.title, status: updated.status }, other] })
  expect(client.getQueryData(['roadmaps', '41'])).toEqual({ ...roadmap, courses: [{ ...planCourse, title: updated.title, status: updated.status }, other] })
  expect(client.getQueryData(['roadmaps', '42'])).toEqual({ ...roadmap, id: 42 })
})

test('늦은 로드맵 읽기는 저장한 코스 제목을 덮어쓰지 않는다', async () => {
  const { client, wrapper } = setup()
  const old = { id: 41, title: '로드맵', status: 'REVIEWING', description: '설명', courses: [{ id: 31, title: '자바', status: 'NOT_PLANNED', position: 1, description: '계획', concepts: [], progress: 'NOT_PLANNED' }] }
  const updated = { ...course, title: '고친 이름', status: 'NOT_PLANNED', roadmapId: 41 }
  let finish!: (response: Response) => void
  client.setQueryData(['roadmaps', '41'], old)
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => options.method === 'GET'
    ? new Promise<Response>((resolve) => { finish = resolve }) : Promise.resolve(response(updated))))
  const read = renderHook(() => useRoadmap('41'), { wrapper })
  const action = renderHook(() => useCourseAction('31'), { wrapper })
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
  await act(async () => { await action.result.current.mutateAsync({ type: 'title', title: updated.title }) })
  await act(async () => { finish(response(old)) })
  await waitFor(() => expect(read.result.current.data?.courses[0].title).toBe(updated.title))
})
