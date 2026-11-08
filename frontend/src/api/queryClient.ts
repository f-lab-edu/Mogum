import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { markLoginConfirmed } from '../lib/authNavigation'
import { ApiError } from './client'

export function retryApiQuery(failureCount: number, error: unknown) {
  if (error instanceof ApiError && error.code !== 'INTERNAL_ERROR') return false
  return failureCount < 2
}

export function createAuthQueryClient(onUnauthorized: () => void) {
  const onError = (error: unknown) => {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') onUnauthorized()
    if (error instanceof ApiError && error.code === 'ACCOUNT_NOT_APPROVED') {
      void client.invalidateQueries({ queryKey: ['me'], exact: true, refetchType: 'all' })
    }
  }

  const client = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        // 나 읽기의 실패는 RequireAuth의 기존 오류 화면에서 처리한다.
        if (error instanceof ApiError && error.code === 'ACCOUNT_NOT_APPROVED'
          && query.queryKey.length === 1 && query.queryKey[0] === 'me') return
        onError(error)
      },
      onSuccess: (_data, query) => {
        if (query.queryKey.length === 1 && query.queryKey[0] === 'me') markLoginConfirmed()
      },
    }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: { retry: retryApiQuery },
      mutations: { retry: false },
    },
  })
  return client
}
