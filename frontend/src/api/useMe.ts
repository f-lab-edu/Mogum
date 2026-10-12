import { useQuery } from '@tanstack/react-query'
import { apiFetch } from './client'
import { retryApiQuery } from './queryClient'

export interface Me {
  email: string
  status: 'PENDING' | 'ACTIVE' | 'WAITLISTED'
  role: 'USER' | 'ADMIN'
  termsAgreed: boolean
  waitlistPosition: number | null
}

export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => apiFetch<Me>('/me'),
    retry: retryApiQuery,
  })
}
