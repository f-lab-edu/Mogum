import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError, CONNECTION_ERROR_MESSAGE } from '../api/client'
import { useReviewAction, useTodayReview } from '../api/useReview'
import type { Rating, ReviewCard, TodayReviewResponse } from '../api/useReview'
import Button from '../components/Button'
import FailureNotice from '../components/FailureNotice'
import InlineConfirmation from '../components/InlineConfirmation'
import Mogumi from '../components/Mogumi'
import { formatReviewInterval } from '../lib/formatReviewInterval'

const ratings: { rating: Rating; label: string }[] = [
  { rating: 'AGAIN', label: '다시' }, { rating: 'HARD', label: '어려움' },
  { rating: 'GOOD', label: '좋음' }, { rating: 'EASY', label: '쉬움' },
]
type Notice = { title: string; message: string }
function message(error: unknown) { return error instanceof ApiError ? error.message : CONNECTION_ERROR_MESSAGE }

function Card({ card, focusQuestion, onDone, onStart, onFailure }: {
  card: ReviewCard
  focusQuestion: boolean
  onDone: () => void
  onStart: () => void
  onFailure: (notice: Notice, refresh: boolean) => void
}) {
  const [answer, setAnswer] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const action = useReviewAction()
  const submitting = useRef(false)
  const question = useRef<HTMLHeadingElement>(null)
  const answerHeading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { if (focusQuestion) question.current?.focus() }, [focusQuestion])
  useEffect(() => { if (answer) answerHeading.current?.focus() }, [answer])

  const submit = (rating?: Rating) => {
    if (submitting.current) return
    submitting.current = true
    onStart()
    action.mutate(rating ? { type: 'review', cardId: card.id, rating } : { type: 'delete', cardId: card.id }, {
      onSuccess: onDone,
      onError: (error) => onFailure({ title: rating ? '평가를 저장하지 못했어요.' : '카드를 지우지 못했어요.', message: message(error) },
        error instanceof ApiError && (error.code === 'CARD_REVIEW_NOT_ALLOWED' || error.code === 'NOT_FOUND')),
      onSettled: () => { submitting.current = false },
    })
  }

  return <>
    <InlineConfirmation open={confirming} onOpenChange={setConfirming} disabled={action.isPending} onConfirm={() => submit()}
      triggerLabel="카드 지우기" title="카드를 지울까요?" confirmLabel="지우기"
      message="이 카드와 이 카드의 복습 기록이 사라져요. 되돌릴 수 없어요. 카드에 연결된 개념은 남아요." />
    <article className="min-w-0 space-y-6 rounded-panel border border-border bg-surface p-6 sm:p-8">
      <div className="space-y-3">
        <p className="text-small font-bold text-primary">질문</p>
        <h2 ref={question} tabIndex={-1} className="whitespace-pre-wrap text-title font-extrabold">{card.question}</h2>
      </div>
      {answer ? <>
        <section aria-labelledby="review-answer" className="space-y-3 rounded-control bg-surface-soft p-4">
          <h3 ref={answerHeading} id="review-answer" tabIndex={-1} className="font-bold text-primary">답</h3>
          <p className="whitespace-pre-wrap">{card.answer}</p>
        </section>
        <div role="group" aria-label="스스로 평가하기" className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {ratings.map(({ rating, label }) => <Button key={rating} variant={rating === 'GOOD' ? 'primary' : 'secondary'}
            disabled={action.isPending || confirming} onClick={() => submit(rating)}>
            <span className="block">{label}</span>{' '}<span className="block text-small font-semibold">{formatReviewInterval(card.nextIntervalsSeconds[rating])}</span>
          </Button>)}
        </div>
      </> : <Button className="w-full" disabled={action.isPending || confirming} onClick={() => setAnswer(true)}>답 보기</Button>}
    </article>
  </>
}

function ReviewCards({ data, refresh, onStart, onFailure }: {
  data: TodayReviewResponse
  refresh: () => void
  onStart: () => void
  onFailure: (notice: Notice, refresh: boolean) => void
}) {
  const [index, setIndex] = useState(0)
  const card = data.cards[index]
  return <>
    <p role="status" className="font-bold text-primary">{data.remainingCount - index}장 남음</p>
    <Card key={index} card={card} focusQuestion={index > 0} onStart={onStart} onFailure={onFailure} onDone={() => {
      if (index + 1 < data.cards.length) setIndex(index + 1)
      else refresh()
    }} />
  </>
}

export default function ReviewPage() {
  const [params] = useSearchParams()
  const courseId = params.get('courseId')
  const review = useTodayReview(courseId)
  const navigate = useNavigate()
  const heading = useRef<HTMLHeadingElement>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  useEffect(() => { document.title = '복습 · 머금'; heading.current?.focus() }, [])
  const refresh = () => { void review.refetch() }
  const allReviews = <Button onClick={() => void navigate('/review')}>전체 복습하기</Button>

  return <div className="min-w-0 space-y-6">
    <h1 ref={heading} tabIndex={-1} className="text-title font-extrabold">복습</h1>
    {notice && <FailureNotice {...notice} />}
    {review.isPending || review.isFetching ? <p role="status" className="text-text-secondary">복습할 카드를 불러오고 있어요.</p>
      : review.isError || !review.data ? <FailureNotice title="복습할 카드를 불러오지 못했어요." message={message(review.error)}>
        <div className="flex flex-wrap gap-3"><Button onClick={refresh}>다시 시도</Button>{courseId !== null && allReviews}</div>
      </FailureNotice> : <>
        {courseId !== null && <p className="font-bold">{review.data.courseTitle}</p>}
        {review.data.cards.length === 0 ? <section className="space-y-6 rounded-panel border border-border bg-surface p-6 text-center sm:p-8">
          <Mogumi />
          <h2 className="text-title font-extrabold">{courseId === null ? '오늘 복습할 카드가 없어요' : '이 코스는 오늘 복습할 카드가 없어요'}</h2>
          {courseId !== null && allReviews}
        </section> : <ReviewCards key={review.dataUpdatedAt} data={review.data} refresh={refresh} onStart={() => setNotice(null)}
          onFailure={(failure, shouldRefresh) => { setNotice(failure); if (shouldRefresh) refresh() }} />}
      </>}
  </div>
}
