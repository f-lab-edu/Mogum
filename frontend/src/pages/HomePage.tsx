import type { UseQueryResult } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useHomeContinueLearning, useHomeJobs, useHomeReviews, useHomeStreak, useHomeTodos } from '../api/useHome'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'
import StatusBadge from '../components/StatusBadge'

const primaryLink = 'inline-flex min-h-13 items-center justify-center rounded-control bg-primary px-6 py-3 font-bold text-on-primary shadow-button hover:bg-primary-hover active:translate-y-button-press active:shadow-button-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary'
const itemLink = 'flex min-h-13 flex-col items-start gap-2 rounded-control border border-border p-4 hover:border-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary'
const todoKinds = new Map([
  ['COURSE_REVIEW', { path: 'courses', message: '검토를 기다려요' }],
  ['ROADMAP_REVIEW', { path: 'roadmaps', message: '검토를 기다려요' }],
  ['ROADMAP_NEXT_COURSE', { path: 'roadmaps', message: '다음 코스를 만들 수 있어요' }],
])
const jobKinds = new Map([
  ['NEW_COURSE', '강의 목록을 짜고 있어요'],
  ['NEW_ROADMAP', '로드맵 계획을 짜고 있어요'],
  ['PLAN_ROADMAP_COURSE', '강의 목록을 짜고 있어요'],
  ['REPLAN_COURSE', '강의 목록을 짜고 있어요'],
  ['REPLAN_ROADMAP', '로드맵 계획을 짜고 있어요'],
  ['MATERIAL', '강의 자료를 만들고 있어요'],
])

function HomeSection({ label, query, children, empty = false, streak = false, emphasis = false }: {
  label: string
  query: UseQueryResult<unknown>
  children: ReactNode
  empty?: boolean
  streak?: boolean
  emphasis?: boolean
}) {
  if (query.isSuccess && empty) return null
  return <section aria-label={label} className={streak
    ? 'rounded-control bg-surface-soft p-4'
    : `space-y-4 rounded-panel border bg-surface p-6 sm:p-8 ${emphasis ? 'border-primary' : 'border-border'}`}>
    {!streak && <h2 className={emphasis ? 'text-title font-extrabold' : 'text-body font-bold'}>{label}</h2>}
    {query.isPending ? <p role="status" className="text-text-secondary">{label} 정보를 불러오고 있어요.</p>
      : query.isError ? <FailureNotice title={`${label} 정보를 불러오지 못했어요.`} message={query.error instanceof ApiError ? query.error.message : CONNECTION_ERROR_MESSAGE}>
        <Button disabled={query.isFetching} onClick={() => void query.refetch()}>다시 시도</Button>
      </FailureNotice> : children}
  </section>
}

export default function HomePage() {
  const streak = useHomeStreak()
  const reviews = useHomeReviews()
  const learning = useHomeContinueLearning()
  const todos = useHomeTodos()
  const jobs = useHomeJobs()
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    document.title = '홈 · 머금'
    heading.current?.focus()
  }, [])

  const course = learning.data?.course
  const nextLesson = course?.nextLesson
  const todoItems = todos.data?.items.filter((item) => todoKinds.has(item.type)) ?? []
  const jobItems = jobs.data?.jobs.filter((job) => jobKinds.has(job.type) && ['QUEUED', 'RUNNING', 'FAILED'].includes(job.status)) ?? []

  return <div className="space-y-6">
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">머금</h1>
    <HomeSection label="스트릭" query={streak} streak>
      {streak.data && <p className="font-bold text-primary">{streak.data.filledToday ? `${streak.data.days}일 연속 · 오늘 채웠어요`
        : streak.data.days >= 1 ? `${streak.data.days}일 연속 · 복습 1장이나 강의 하나로 이어 가요`
          : '오늘 복습 1장이나 강의 하나로 시작해요'}</p>}
    </HomeSection>
    <HomeSection label="오늘 복습" query={reviews} emphasis>
      {reviews.data && (reviews.data.remainingCount > 0 ? <>
        <p className="text-title font-extrabold text-primary">복습할 카드 {reviews.data.remainingCount}장</p>
        <Link to="/review" className={`${primaryLink} w-full sm:w-auto`}>복습 시작</Link>
      </> : <p className="text-text-secondary">오늘 복습할 카드가 없어요</p>)}
    </HomeSection>
    <HomeSection label="이어서 배우기" query={learning} empty={!nextLesson}>
      {course && nextLesson && <Link to={`/courses/${course.id}/lessons/${nextLesson.id}`} className={itemLink}>
        <span className="font-bold">{course.title}</span>
        <span className="text-small text-primary">{nextLesson.title}</span>
      </Link>}
    </HomeSection>
    <HomeSection label="할 일" query={todos} empty={todoItems.length === 0}>
      <ul aria-label="할 일 목록" className="space-y-4">
        {todoItems.map((item) => <li key={`${item.type}-${item.targetId}`}>
          <Link to={`/${todoKinds.get(item.type)?.path}/${item.targetId}`} className={itemLink}>
            <span className="font-bold">{item.title}</span>
            <span className="text-small text-text-secondary">{todoKinds.get(item.type)?.message}</span>
          </Link>
        </li>)}
      </ul>
    </HomeSection>
    <HomeSection label="만드는 중 · 실패" query={jobs} empty={jobItems.length === 0}>
      <ul aria-label="만드는 중 · 실패 목록" className="space-y-4">
        {jobItems.map((job) => <li key={job.id}>
          <Link to={`/jobs/${job.id}`} className={itemLink}>
            <span className="font-bold">{job.type === 'NEW_COURSE' || job.type === 'NEW_ROADMAP' ? job.subject.topic : job.subject.title}</span>
            <StatusBadge>{job.status === 'FAILED' ? '만들지 못했어요'
              : job.status === 'QUEUED' ? '차례를 기다리고 있어요' : jobKinds.get(job.type)}</StatusBadge>
          </Link>
        </li>)}
      </ul>
    </HomeSection>
    <Link to="/new" className={primaryLink}>새로 배우기</Link>
  </div>
}
