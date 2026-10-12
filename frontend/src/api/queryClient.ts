import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { markLoginConfirmed } from '../lib/authNavigation'
import { ApiError } from './client'

export function retryApiQuery(failureCount: number, error: unknown) {
  if (error instanceof ApiError && error.status < 500) return false
  return failureCount < 2
}

export function createAuthQueryClient(onUnauthorized: () => void) {
  const onError = (error: unknown) => {
    if (error instanceof ApiError && error.status === 401) onUnauthorized()
  }

  return new QueryClient({
    queryCache: new QueryCache({
      onError,
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
}
