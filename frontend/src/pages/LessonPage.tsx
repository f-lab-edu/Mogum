import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { receiveJob } from '../api/useJob'
import { useLesson, useLessonCompletion } from '../api/useLesson'
import Button from '../components/Button'
import CreationBlockedNotice, { isCreationBlockedError } from '../components/CreationBlockedNotice'
import FailureNotice from '../components/FailureNotice'
import MaterialBody from '../components/MaterialBody'

function message(error: unknown) {
  return error instanceof ApiError ? error.message : CONNECTION_ERROR_MESSAGE
}

export default function LessonPage() {
  const { courseId = '', lessonId = '' } = useParams()
  const navigate = useNavigate()
  const lesson = useLesson(lessonId)
  const completion = useLessonCompletion(lessonId)
  const requested = useRef(false)
  const submitting = useRef(false)
  const receive = useMutation({ mutationFn: receiveJob, retry: false })
  const { mutate: requestMaterial } = receive
  const data = lesson.data
  const ready = !lesson.isFetching && !lesson.isError && data !== undefined

  useEffect(() => {
    if (!ready || data.material || data.job || requested.current) return
    requested.current = true
    requestMaterial({ type: 'MATERIAL', lessonId: data.id })
  }, [ready, data, requestMaterial])

  const back = <Link to={`/courses/${data?.courseId ?? courseId}`} className="inline-flex min-h-11 items-center rounded-google font-bold text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">코스로 돌아가기</Link>

  if (lesson.isPending || lesson.isFetching) return <p role="status" className="text-text-secondary">강의를 불러오고 있어요.</p>
  if (lesson.isError || !data) return <FailureNotice title="강의를 불러오지 못했어요." message={lesson.isError ? message(lesson.error) : '다시 불러와 주세요.'}>{back}</FailureNotice>
  if (!data.material) {
    if (data.job) return <Navigate to={`/jobs/${data.job.id}`} replace />
    if (receive.data) return <Navigate to={`/jobs/${receive.data.id}`} replace />
    if (receive.isError) return isCreationBlockedError(receive.error)
      ? <CreationBlockedNotice error={receive.error}>{back}</CreationBlockedNotice>
      : <FailureNotice title="강의 자료를 요청하지 못했어요." message={message(receive.error)}>{back}</FailureNotice>
    return <p role="status" className="text-text-secondary">강의 자료를 요청하고 있어요.</p>
  }

  const complete = () => {
    if (submitting.current) return
    submitting.current = true
    completion.mutate(undefined, {
      onSuccess: (result) => { void navigate(`/courses/${result.courseId}${result.courseCompleted ? '/done' : ''}`, { replace: true }) },
      onSettled: () => { submitting.current = false },
    })
  }

  return <article className="min-w-0 space-y-6 rounded-panel border border-border bg-surface p-6 sm:p-8">
    <h2 className="text-title font-extrabold">{data.position}강 · {data.title}</h2>
    <MaterialBody body={data.material.body} />
    <div className="space-y-4 border-t border-border pt-6">
      {data.completed ? <p className="font-bold text-primary">학습 완료한 강의예요.</p> : <>
        <p id="lesson-completion-notice" className="text-small text-text-secondary">학습 완료는 되돌릴 수 없어요. 다시 열어 읽을 수 있어요.</p>
        <Button disabled={completion.isPending} aria-describedby="lesson-completion-notice" onClick={complete}>학습 완료</Button>
      </>}
      {completion.isError && <FailureNotice title="학습 완료를 저장하지 못했어요." message={message(completion.error)} />}
    </div>
  </article>
}
