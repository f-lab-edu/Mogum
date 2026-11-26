import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useCourse, useCourseAction, useCourseDeletion } from '../api/useCourse'
import { receiveJob } from '../api/useJob'
import Button from '../components/Button'
import CreationBlockedNotice, { isCreationBlockedError } from '../components/CreationBlockedNotice'
import FailureNotice from '../components/FailureNotice'
import InlineConfirmation from '../components/InlineConfirmation'
import Mogumi from '../components/Mogumi'
import ProgressBar from '../components/ProgressBar'
import StatusBadge from '../components/StatusBadge'
import TextField from '../components/TextField'

const statuses = new Map([['REVIEWING', '검토 중'], ['LEARNING', '학습 중'], ['COMPLETED', '다 마침']])
const koreanDate = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' })

function message(error: unknown) {
  return error instanceof ApiError ? error.message : CONNECTION_ERROR_MESSAGE
}

function fieldError(error: unknown, field: string) {
  return error instanceof ApiError && error.code === 'FIELD_INVALID'
    ? error.fields?.find((violation) => violation.field === field)?.reason : undefined
}

function PageLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link to={to} className="inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">{children}</Link>
}

export default function CoursePage() {
  const { courseId = '' } = useParams()
  const navigate = useNavigate()
  const course = useCourse(courseId)
  const action = useCourseAction(courseId)
  const deletion = useCourseDeletion(courseId)
  const [confirming, setConfirming] = useState(false)
  const submitting = useRef(false)
  const [editingTitle, setEditingTitle] = useState(false)
  const [title, setTitle] = useState('')
  const [replanOpen, setReplanOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const titleForm = useRef<HTMLFormElement>(null)
  const replanForm = useRef<HTMLFormElement>(null)
  const titleButton = useRef<HTMLButtonElement>(null)
  const returnTitleFocus = useRef(false)
  const replanButton = useRef<HTMLButtonElement>(null)
  const replan = useMutation({
    mutationFn: receiveJob,
    retry: false,
    onSuccess: (job) => { void navigate(`/jobs/${job.id}`) },
    onSettled: () => { submitting.current = false },
  })
  const busy = action.isPending || replan.isPending || deletion.isPending
  const data = course.data
  const status = data && statuses.get(data.status)

  useEffect(() => {
    if (editingTitle) titleForm.current?.querySelector('input')?.focus()
    else if (returnTitleFocus.current) {
      titleButton.current?.focus()
      returnTitleFocus.current = false
    }
  }, [editingTitle])
  useEffect(() => {
    if (replanOpen) replanForm.current?.querySelector('textarea')?.focus()
  }, [replanOpen])

  const closeTitle = () => {
    returnTitleFocus.current = true
    setEditingTitle(false)
  }
  const save = (next: { type: 'start' } | { type: 'title'; title: string }) => {
    if (submitting.current) return
    submitting.current = true
    action.mutate(next, {
      onSuccess: () => { if (next.type === 'title') closeTitle() },
      onSettled: () => { submitting.current = false },
    })
  }

  if (course.isPending && courseId) return <p role="status" className="text-text-secondary">코스를 불러오고 있어요.</p>
  if (course.isError || !data || (!status && data.status !== 'NOT_PLANNED')) {
    return <FailureNotice title="코스를 불러오지 못했어요." message={course.isError ? message(course.error) : '다시 불러와 주세요.'}>
      <div className="flex flex-wrap items-center gap-4">
        <Button disabled={course.isFetching} onClick={() => void course.refetch()}>다시 시도</Button>
        <PageLink to="/">홈으로</PageLink>
      </div>
    </FailureNotice>
  }
  if (data.status === 'NOT_PLANNED') {
    return data.roadmapId === undefined ? <PageLink to="/">홈으로</PageLink>
      : <PageLink to={`/roadmaps/${data.roadmapId}`}>로드맵 화면으로 가기</PageLink>
  }

  const reviewing = data.status === 'REVIEWING'
  const learning = data.status === 'LEARNING'
  const completed = data.status === 'COMPLETED'
  const nextLesson = learning ? data.lessons.find((lesson) => lesson.id === data.nextLessonId) : undefined
  const titleValid = title.trim().length > 0 && Array.from(title).length <= 100

  return <div className="space-y-6">
    <section className="space-y-6 rounded-panel border border-border bg-surface p-6 sm:p-8" aria-label="코스 정보">
      <div className={`flex gap-6 ${completed ? 'flex-col items-center text-center sm:flex-row sm:text-left' : 'flex-col sm:flex-row sm:items-start'}`}>
        {completed && <Mogumi />}
        <div className="min-w-0 flex-1 space-y-4">
          <StatusBadge>{status}</StatusBadge>
          <h2 className="text-title font-extrabold">{data.title}</h2>
          <Button ref={titleButton} variant="secondary" disabled={busy || editingTitle} onClick={() => {
            setTitle(data.title)
            action.reset()
            setEditingTitle(true)
          }}>코스 제목 고치기</Button>
          {editingTitle && <form ref={titleForm} noValidate className="space-y-4 text-left" onSubmit={(event) => {
            event.preventDefault()
            if (titleValid) save({ type: 'title', title })
          }}>
            <p className="text-small text-text-secondary">보이는 이름만 바뀌어요. 강의 내용은 그대로예요.</p>
            <TextField label="코스 제목" value={title} onChange={(value) => { setTitle(value); action.reset() }} required maxCodePoints={100} disabled={busy} error={fieldError(action.error, 'title')} />
            <div className="flex flex-wrap gap-3">
              <Button type="submit" disabled={busy || !titleValid}>제목 저장</Button>
              <Button variant="secondary" disabled={busy} onClick={closeTitle}>제목 수정 취소</Button>
            </div>
          </form>}
          {completed && <>
            <p>이 코스의 모든 강의를 다 마쳤어요.</p>
            {data.completedAt && <p className="text-small text-text-secondary">다 마친 날 · <time dateTime={data.completedAt}>{koreanDate.format(new Date(data.completedAt))}</time></p>}
            {data.roadmapId !== undefined && <PageLink to={`/roadmaps/${data.roadmapId}`}>로드맵 화면으로 가기</PageLink>}
          </>}
        </div>
        {!completed && <Mogumi />}
      </div>
      {learning && <ProgressBar label="학습 진도" completed={data.progress.completedLessons} total={data.progress.totalLessons} />}
    </section>

    {action.isError && <FailureNotice title={action.variables?.type === 'title' ? '제목을 바꾸지 못했어요.' : '시작하지 못했어요.'} message={message(action.error)}>
      <p className="text-small">안내를 확인한 뒤 같은 버튼을 다시 눌러 주세요.</p>
    </FailureNotice>}

    {reviewing && <section className="space-y-4 rounded-panel border border-border bg-surface p-6 sm:p-8" aria-label="코스 검토">
      {data.masteryReport && (data.masteryReport.shortened > 0 || data.masteryReport.revisited > 0) && <p className="rounded-control bg-surface-soft p-4 text-small text-text-secondary">
        이전 복습 기록을 바탕으로 {data.masteryReport.shortened}개 개념을 짧게 다뤘고 {data.masteryReport.revisited}개 개념을 다시 짚었어요.
      </p>}
      <p className="text-text-secondary">강의는 아직 열 수 없어요. 목록을 살펴보고 시작해 주세요.</p>
      <div className="flex flex-col gap-3 sm:flex-row sm:justify-end">
        <Button ref={replanButton} variant="secondary" disabled={busy} aria-expanded={replanOpen} aria-controls={replanOpen ? 'course-replan' : undefined} onClick={() => {
          setReplanOpen(!replanOpen)
          replan.reset()
        }}>다시 짜기</Button>
        <Button disabled={busy} onClick={() => save({ type: 'start' })}>이대로 시작</Button>
      </div>
      {replanOpen && <form id="course-replan" ref={replanForm} noValidate className="space-y-4 rounded-control bg-surface-soft p-4" onSubmit={(event) => {
        event.preventDefault()
        if (Array.from(feedback).length > 500 || submitting.current) return
        submitting.current = true
        replan.mutate({ type: 'REPLAN_COURSE', courseId: data.id, ...(feedback.trim() ? { feedback } : {}) })
      }}>
        <p className="text-small text-text-secondary">강의 목록에서 바꾸고 싶은 점을 알려 주세요.</p>
        <TextField label="고칠 점" value={feedback} onChange={(value) => { setFeedback(value); replan.reset() }} multiline maxCodePoints={500} disabled={busy} error={fieldError(replan.error, 'feedback')} />
        <div className="flex flex-wrap gap-3">
          <Button type="submit" disabled={busy || Array.from(feedback).length > 500}>다시 짜기 요청</Button>
          <Button variant="secondary" disabled={busy} onClick={() => { setReplanOpen(false); replanButton.current?.focus() }}>다시 짜기 취소</Button>
        </div>
      </form>}
      {replan.isError && (isCreationBlockedError(replan.error) ? <CreationBlockedNotice error={replan.error} /> : <FailureNotice title="다시 짜기를 요청하지 못했어요." message={message(replan.error)}>
        <p className="text-small">안내를 확인한 뒤 다시 짜기 요청을 다시 눌러 주세요.</p>
      </FailureNotice>)}
    </section>}

    {nextLesson && <section aria-label="다음에 열 강의" className="space-y-3 rounded-panel bg-primary p-6 text-on-primary sm:p-8">
      <p className="font-bold">다음에 열 강의</p>
      <h2 className="text-title font-extrabold"><Link to={`/courses/${data.id}/lessons/${nextLesson.id}`} className="inline-flex min-h-11 items-center rounded-google underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-on-primary">{nextLesson.position}강 · {nextLesson.title}</Link></h2>
      <p>{nextLesson.description}</p>
    </section>}

    <section className="space-y-4" aria-labelledby="course-lessons-heading">
      <h2 id="course-lessons-heading" className="font-extrabold">강의 목록</h2>
      <ol aria-label="강의 목록" className="space-y-4">
        {data.lessons.map((lesson) => <li key={lesson.id} className={`space-y-3 rounded-panel border bg-surface p-6 sm:p-8 ${nextLesson?.id === lesson.id ? 'border-primary' : 'border-border'}`}>
          {lesson.completed && <p className="text-small font-bold text-primary">학습 완료</p>}
          <h3 className="font-bold">{(learning || completed) && (lesson.completed || lesson.id === data.nextLessonId)
            ? <PageLink to={`/courses/${data.id}/lessons/${lesson.id}`}>{lesson.position}강 · {lesson.title}</PageLink>
            : <>{lesson.position}강 · {lesson.title}</>}</h3>
          {(learning || completed) && !lesson.completed && lesson.id !== data.nextLessonId && <p className="text-small text-text-secondary">앞 강의를 학습 완료하면 열 수 있어요.</p>}
          <p className="text-text-secondary">{lesson.description}</p>
          <dl className="flex flex-wrap gap-3">
            {lesson.concepts.map((concept, index) => <div key={index} className="rounded-control bg-surface-soft px-3 py-2 text-small">
              <dt className="font-bold text-primary">{concept.name}</dt>
              <dd className="text-text-secondary">{concept.definition}</dd>
            </div>)}
          </dl>
        </li>)}
      </ol>
    </section>

    {(learning || completed) && data.todayReviewCount > 0 && <div>
      <PageLink to={`/review?courseId=${data.id}`}>이 코스 복습하기 · {data.todayReviewCount}장</PageLink>
    </div>}

    {data.roadmapId === undefined && <InlineConfirmation open={confirming} onOpenChange={setConfirming} disabled={busy}
      triggerLabel="코스 지우기" title="코스를 지울까요?" confirmLabel="지우기"
      message={reviewing ? '이 코스의 강의 목록이 사라져요. 되돌릴 수 없어요.' : '강의는 사라지고 카드는 복습에 나오지 않아요. 그동안의 카드와 복습 기록은 다음 강의를 내 수준에 맞추는 데 계속 쓰여요.'}
      onConfirm={() => {
        if (submitting.current) return
        submitting.current = true
        deletion.mutate(undefined, {
          onSuccess: () => { void navigate('/courses', { replace: true }) },
          onSettled: () => { submitting.current = false },
        })
      }} />}
    {deletion.isError && <FailureNotice title="코스를 지우지 못했어요." message={message(deletion.error)}>
      <p className="text-small">안내를 확인한 뒤 지우기를 다시 눌러 주세요.</p>
    </FailureNotice>}
  </div>
}
