import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client'
import type { ApiErrorCode } from './client'

type Extensible<T extends string> = T | (string & Record<never, never>)

export interface JobResponse {
  id: number
  type: Extensible<'NEW_COURSE' | 'NEW_ROADMAP' | 'PLAN_ROADMAP_COURSE' | 'REPLAN_COURSE' | 'REPLAN_ROADMAP' | 'MATERIAL'>
  status: Extensible<'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'>
  subject: {
    topic?: string
    level?: Extensible<'BEGINNER' | 'BASICS' | 'FAMILIAR' | 'OTHER'>
    levelOther?: string
    purpose?: Extensible<'WORK' | 'INTERVIEW' | 'GENERAL' | 'OTHER'>
    purposeOther?: string
    title?: string
  }
  result?: { type: Extensible<'COURSE' | 'ROADMAP' | 'LESSON'>; id: number; courseId?: number }
  error?: { code: ApiErrorCode; message: string }
}

export const jobKey = (jobId: string) => ['jobs', jobId] as const

export interface NewLearningRequest {
  type: 'NEW_COURSE' | 'NEW_ROADMAP'
  topic: string
  level: 'BEGINNER' | 'BASICS' | 'FAMILIAR' | 'OTHER'
  levelOther?: string
  purpose: 'WORK' | 'INTERVIEW' | 'GENERAL' | 'OTHER'
  purposeOther?: string
  memo?: string
}

export interface ReplanCourseRequest {
  type: 'REPLAN_COURSE'
  courseId: number
  feedback?: string
}

export interface PlanRoadmapCourseRequest {
  type: 'PLAN_ROADMAP_COURSE'
  courseId: number
}

export interface ReplanRoadmapRequest {
  type: 'REPLAN_ROADMAP'
  roadmapId: number
  feedback?: string
}

export interface MaterialRequest {
  type: 'MATERIAL'
  lessonId: number
}

export function receiveJob(request: NewLearningRequest | ReplanCourseRequest | PlanRoadmapCourseRequest | ReplanRoadmapRequest | MaterialRequest) {
  return apiFetch<JobResponse>('/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
}

export function readJob(jobId: string, signal?: AbortSignal) {
  return apiFetch<JobResponse>(`/jobs/${encodeURIComponent(jobId)}`, { signal })
}

export function retryJob(jobId: string) {
  return apiFetch<JobResponse>(`/jobs/${encodeURIComponent(jobId)}/retry`, { method: 'POST' })
}

export function discardJob(jobId: string) {
  return apiFetch<void>(`/jobs/${encodeURIComponent(jobId)}/discard`, { method: 'POST' })
}

export function useJob(jobId: string) {
  return useQuery({
    queryKey: jobKey(jobId),
    queryFn: ({ signal }) => readJob(jobId, signal),
    enabled: jobId.length > 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchInterval: ({ state }) => state.status !== 'error'
      && (state.data?.status === 'QUEUED' || state.data?.status === 'RUNNING') ? 2000 : false,
  })
}

export function useJobAction(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationKey: jobKey(jobId),
    mutationFn: async (action: 'retry' | 'discard') => action === 'retry' ? retryJob(jobId) : discardJob(jobId),
    retry: false,
    onSuccess: async (result, action) => {
      await client.cancelQueries({ queryKey: jobKey(jobId), exact: true })
      if (action === 'retry') client.setQueryData(jobKey(jobId), result)
      else client.removeQueries({ queryKey: jobKey(jobId), exact: true })
    },
  })
}
