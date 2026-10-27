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

test('서버가 돌려준 현재 사용자 필드를 Query에 보관한다', async () => {
  const me = { email: 'admin@example.com', status: 'ACTIVE', role: 'ADMIN', termsAgreed: false, waitlistPosition: null }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: me }))))
  const client = createAuthQueryClient(vi.fn())
  const { result } = renderHook(useMe, { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
  await waitFor(() => expect(result.current.data).toEqual(me))
  expect(fetch).toHaveBeenCalledWith('/api/me', expect.objectContaining({ credentials: 'include' }))
  client.clear()
})
