import { QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { createAuthQueryClient } from './queryClient'
import { useMe } from './useMe'

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
