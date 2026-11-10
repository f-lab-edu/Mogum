import { useQuery } from '@tanstack/react-query'
import { apiFetch } from './client'
import { retryApiQuery } from './queryClient'

export interface Me {
  email: string
  name: string
  status: 'NOT_APPLIED' | 'APPLIED' | 'APPROVED' | 'REJECTED' | (string & Record<never, never>)
}

export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => apiFetch<Me>('/me'),
    retry: retryApiQuery,
  })
}
