import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useCourses } from '../api/useCourse'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'
import StatusBadge from '../components/StatusBadge'

const statuses = new Map([['REVIEWING', '검토 중'], ['LEARNING', '학습 중'], ['COMPLETED', '다 마침']])

export default function CoursesPage() {
  const courses = useCourses()
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    document.title = '코스 · 머금'
    heading.current?.focus()
  }, [])

  return <div className="space-y-6">
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">코스</h1>
    {courses.isPending ? <p role="status" className="text-text-secondary">코스 목록을 불러오고 있어요.</p>
      : courses.isError || !courses.data ? <FailureNotice title="코스 목록을 불러오지 못했어요." message={courses.error instanceof ApiError ? courses.error.message : CONNECTION_ERROR_MESSAGE}>
        <Button disabled={courses.isFetching} onClick={() => void courses.refetch()}>다시 시도</Button>
      </FailureNotice>
        : courses.data.courses.length === 0 ? <p className="text-text-secondary">아직 만든 코스가 없어요</p>
          : <ul aria-label="코스 목록" className="space-y-4">
            {courses.data.courses.map((course) => <li key={course.id}>
              <Link to={`/courses/${course.id}`} className="flex min-h-13 flex-wrap items-center justify-between gap-3 rounded-panel border border-border bg-surface p-6 hover:border-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
                <span className="min-w-0 font-bold">{course.title}</span>
                {statuses.has(course.status) && <StatusBadge>{statuses.get(course.status)}</StatusBadge>}
              </Link>
            </li>)}
          </ul>}
  </div>
}
