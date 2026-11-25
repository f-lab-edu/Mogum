import { QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { createAuthQueryClient } from './queryClient'
import { useMe } from './useMe'
import type { Me } from './useMe'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

test.each(['NOT_APPLIED', 'APPLIED', 'APPROVED', 'REJECTED', 'FUTURE_STATUS'])('서버의 %s 상태와 확장된 필드를 오류 없이 읽는다', async (status) => {
  const me = { email: 'admin@example.com', status, name: '민서', futureField: '새로운 칸' }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: me }))))
  const client = createAuthQueryClient(vi.fn())
  const { result } = renderHook(useMe, { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
  await waitFor(() => expect(result.current.data).toEqual(me))
  expect(fetch).toHaveBeenCalledWith('/api/me', expect.objectContaining({ credentials: 'include' }))
  client.clear()
})

test('승인된 나의 선택 크레딧 칸을 서버 날짜 문자열 그대로 읽는다', async () => {
  const me: Me = { status: 'APPROVED', email: 'admin@example.com', name: '민서', credit: { balance: 18, nextRefillDate: '2026-12-02' } }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: me }))))
  const client = createAuthQueryClient(vi.fn())
  const { result } = renderHook(useMe, { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
  await waitFor(() => expect(result.current.data?.credit).toEqual(me.credit))
  client.clear()
})
