package com.mogum.worker;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.service.CreditLedger;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.config.WorkerProperties;
import com.mogum.generation.GeneratedResult;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobGenerator;
import com.mogum.generation.JobSnapshot;
import com.mogum.job.domain.GenerationJob;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobType;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/** 작업 하나를 집어 끝낸다. 실행 스레드와 갱신 스케줄러의 수명은 조립하는 쪽이 관리한다. */
@Slf4j
public final class JobRunner {
	private final EntityManager entityManager;
	private final TransactionTemplate transactionTemplate;
	private final TransactionTemplate releaseTransactionTemplate;
	private final CreditLedger creditLedger;
	private final Clock clock;
	private final WorkerProperties workerProperties;
	private final Map<JobType, JobGenerator> generators;
	private final ScheduledExecutorService renewals;

	// 이 실행기 한 개의 시작/종료 경계다. 다른 워커의 작업 분배는 DB만 결정한다.
	private final Object admission = new Object();
	private volatile boolean stopping;
	private Lease current;

	public JobRunner(EntityManager entityManager, PlatformTransactionManager platformTransactionManager,
		CreditLedger creditLedger, Clock clock, WorkerProperties workerProperties,
		List<JobGenerator> generators, ScheduledExecutorService renewals) {
		this.entityManager = entityManager;
		this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
		this.releaseTransactionTemplate = new TransactionTemplate(platformTransactionManager);
		this.releaseTransactionTemplate.setTimeout(5);
		this.creditLedger = creditLedger;
		this.clock = clock;
		this.workerProperties = workerProperties;
		this.generators = generators.stream()
			.collect(Collectors.toUnmodifiableMap(JobGenerator::type, Function.identity()));
		this.renewals = renewals;
	}

	/** 집은 작업이 있으면 true. 호출자도 트랜잭션 밖이어야 generate 동안 연결을 놓을 수 있다. */
	public boolean runOnce() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("JobRunner requires no ambient transaction");
		}

		final Lease lease;
		synchronized (admission) {
			if (stopping) {
				return false;
			}

			var claimed = transactionTemplate.execute(status -> claim());
			if (claimed == null) {
				return false;
			}

			lease = new Lease(claimed);
			current = lease;
		}

		var job = lease.job;
		putMdc(job);
		try (lease) {
			var generator = generators.get(job.type());
			if (generator == null) {
				throw new IllegalStateException("No generator for claimed job type");
			}

			lease.start();
			GenerationOutcome outcome;
			int retries = 0;
			while (true) {
				if (lease.lost()) {
					return true;
				}

				outcome = generator.generate(job);
				if (lease.lost()) {
					return true;
				}
				if (!(outcome instanceof GenerationOutcome.Failure failure)
					|| !retryable(failure) || retries >= workerProperties.maxRetries()) {
					break;
				}

				retries++;
				try {
					TimeUnit.NANOSECONDS.sleep(workerProperties.retryDelay().toNanos());
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return true; // 저장하지 않고 시한으로 되찾는다.
				}

				// 다음 유료 호출 전에 되찾기·삭제를 확인한다. claim_count는 그대로다.
				lease.refresh();
			}

			// 이미 시작된 갱신이 끝날 때까지 기다려 완료 뒤 0 rows를 시한 상실로 오인하지 않는다.
			lease.close();
			if (lease.lost()) {
				return true;
			}

			if (outcome instanceof GenerationOutcome.Success success) {
				save(job, generator, success.result(), lease);
			} else {
				fail(job, (GenerationOutcome.Failure)outcome, lease);
			}
			return true;
		} finally {
			clearMdc();
			synchronized (admission) {
				current = null;
			}
		}
	}

	/** 이미 꺼내기 시작한 한 바퀴는 마치되 그 다음 작업은 받지 않는다. */
	public void stopClaiming() {
		stopping = true;
	}

	/** 종료 한도 뒤에만 호출한다. DB 소유권이 아직 같을 때만 기다림으로 돌린다. */
	public void releaseCurrent() {
		final Lease lease;
		synchronized (admission) {
			lease = current;
		}
		if (lease == null) {
			return;
		}

		putMdc(lease.job);
		try {
			lease.release();
		} finally {
			clearMdc();
		}
	}

	private JobSnapshot claim() {
		var now = clock.instant();
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		var ids = entityManager.createNativeQuery("""
			WITH candidate AS (
			    SELECT id FROM generation_jobs
			    WHERE status = 'QUEUED' OR (status = 'RUNNING' AND lease_until < :now)
			    ORDER BY created_at, id
			    FOR UPDATE SKIP LOCKED LIMIT 1
			)
			UPDATE generation_jobs j
			SET status = 'RUNNING', claim_count = j.claim_count + 1, lease_until = :until,
			    started_at = :now, finished_at = NULL, error_type = NULL, updated_at = :now
			FROM candidate WHERE j.id = candidate.id RETURNING j.id
			""")
			// @checkstyle:on
			.setParameter("now", now)
			.setParameter("until", now.plus(workerProperties.leaseDuration()))
			.getResultList();
		if (ids.isEmpty()) {
			return null;
		}

		var job = entityManager.find(GenerationJob.class, ((Number)ids.getFirst()).longValue());
		Long target = switch (job.getType()) {
			case NEW_COURSE, NEW_ROADMAP -> null;
			case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> job.getCourseId();
			case REPLAN_ROADMAP -> job.getRoadmapId();
			case MATERIAL -> job.getLessonId();
		};
		var input = job.getInput();
		var values = input == null ? null
			: new JobSnapshot.Input(input.getTopic(), input.getLevel(), input.getLevelOther(),
				input.getPurpose(), input.getPurposeOther(), input.getMemo());
		return new JobSnapshot(job.getId(), job.getUserId(), job.getType(), target,
			values, job.getFeedback(), job.getClaimCount());
	}

	/** 이 조건부 갱신이 트랜잭션의 첫 쓰기다. 끝날 때까지 행 잠금을 유지한다. */
	private boolean guard(JobSnapshot job) {
		return entityManager.createNativeQuery("""
			UPDATE generation_jobs SET updated_at = :now
			WHERE id = :id AND claim_count = :claim AND status = 'RUNNING'
			""")
			.setParameter("now", clock.instant())
			.setParameter("id", job.id())
			.setParameter("claim", job.claimCount())
			.executeUpdate() == 1;
	}

	private void save(JobSnapshot job, JobGenerator generator, GeneratedResult result, Lease lease) {
		boolean saved;
		try {
			saved = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
				if (!guard(job)) {
					return false;
				}

				var ref = generator.save(job, result);
				if (job.targetId() != null && ref.id() != job.targetId()) {
					throw new IllegalStateException("Result must refer to original target");
				}

				entityManager.flush(); // JPA 결과 생성·상태 변경 뒤 네이티브 쓰기 전에 반드시 반영한다.
				String column = switch (job.type()) {
					case NEW_COURSE, PLAN_ROADMAP_COURSE, REPLAN_COURSE -> "course_id";
					case NEW_ROADMAP, REPLAN_ROADMAP -> "roadmap_id";
					case MATERIAL -> "lesson_id";
				};
				entityManager.createNativeQuery(
					"UPDATE generation_jobs SET status = 'SUCCEEDED', " + column + " = :result, "
						+ "finished_at = :now, lease_until = NULL, error_type = NULL, updated_at = :now "
						+ "WHERE id = :id AND claim_count = :claim AND status = 'RUNNING'")
					.setParameter("result", ref.id())
					.setParameter("now", clock.instant())
					.setParameter("id", job.id())
					.setParameter("claim", job.claimCount())
					.executeUpdate();
				return true;
			}));
		} catch (RuntimeException exception) {
			// 입력을 담을 수 있는 메시지·cause·suppressed는 넘기지 않고 실패 위치만 남긴다.
			var safe = new RuntimeException(exception.getClass().getName());
			safe.setStackTrace(exception.getStackTrace());
			log.error("job save failed", safe);
			return; // 롤백된 RUNNING은 시한 뒤 처음부터 되찾는다. 실패·복구로 바꾸지 않는다.
		}

		if (saved) {
			log.info("job finished status=SUCCEEDED");
		} else {
			lease.lose();
		}
	}

	private void fail(JobSnapshot job, GenerationOutcome.Failure failure, Lease lease) {
		boolean failed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
			// 첫 쓰기에서 확인·실패 전환. 지워졌으면 복구는 지우는 쪽이 한다.
			// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
			int rows = entityManager.createNativeQuery("""
				UPDATE generation_jobs SET status = 'FAILED', error_type = :error,
				    finished_at = :now, lease_until = NULL, updated_at = :now
				WHERE id = :id AND claim_count = :claim AND status = 'RUNNING'
				""")
				// @checkstyle:on
				.setParameter("error", JobErrorType.valueOf(failure.name()).name())
				.setParameter("now", clock.instant())
				.setParameter("id", job.id())
				.setParameter("claim", job.claimCount())
				.executeUpdate();
			if (rows == 0) {
				return false;
			}

			creditLedger.restoreIfOwed(job.userId(), job.id());
			return true;
		}));

		if (failed) {
			log.info("job finished status=FAILED");
		} else {
			lease.lose();
		}
	}

	private static boolean retryable(GenerationOutcome.Failure failure) {
		return failure == GenerationOutcome.Failure.PROVIDER_UNAVAILABLE
			|| failure == GenerationOutcome.Failure.INVALID_OUTPUT;
	}

	private static void putMdc(JobSnapshot job) {
		MDC.put(RequestLogFilter.JOB_ID, Long.toString(job.id()));
		MDC.put(RequestLogFilter.USER_ID, Long.toString(job.userId()));
	}

	private static void clearMdc() {
		MDC.remove(RequestLogFilter.JOB_ID);
		MDC.remove(RequestLogFilter.USER_ID);
	}

	/** 한 runOnce 안에서만 공유한다. 워커끼리 나누는 소유권·잠금은 DB에만 있다. */
	private final class Lease implements AutoCloseable {
		private final JobSnapshot job;
		private ScheduledFuture<?> renewal;
		private boolean active = true;
		private boolean lost;

		private Lease(JobSnapshot job) {
			this.job = job;
		}

		private void start() {
			long interval = workerProperties.renewalInterval().toNanos();
			renewal = renewals.scheduleWithFixedDelay(() -> {
				putMdc(job);
				try {
					refresh();
				} catch (RuntimeException ignored) {
					// DB에 닿지 못하면 다음 간격에 다시 확인한다. 0 rows만 소유권 상실의 증거다.

				} finally {
					clearMdc();
				}
			}, interval, interval, TimeUnit.NANOSECONDS);
		}

		private synchronized void refresh() {
			if (!active || lost) {
				return;
			}

			var now = clock.instant();
			int rows = transactionTemplate.execute(status -> entityManager.createNativeQuery("""
				UPDATE generation_jobs SET lease_until = :until, updated_at = :now
				WHERE id = :id AND claim_count = :claim AND status = 'RUNNING'
				""")
				.setParameter("until", now.plus(workerProperties.leaseDuration()))
				.setParameter("now", now)
				.setParameter("id", job.id())
				.setParameter("claim", job.claimCount())
				.executeUpdate());
			if (rows == 0) {
				lose();
			}
		}

		private synchronized boolean lost() {
			return lost;
		}

		private synchronized void lose() {
			if (lost) {
				return;
			}
			lost = true;
			log.warn("job lease lost");
		}

		private synchronized void release() {
			close(); // 진행 중인 갱신을 마친 뒤 취소한다. 반환 뒤에는 늘리지 않는다.
			boolean released = Boolean.TRUE
				.equals(releaseTransactionTemplate.execute(status -> entityManager.createNativeQuery("""
					UPDATE generation_jobs SET status = 'QUEUED', lease_until = NULL, updated_at = :now
					WHERE id = :id AND claim_count = :claim AND status = 'RUNNING'
					""")
					.setParameter("now", clock.instant())
					.setParameter("id", job.id())
					.setParameter("claim", job.claimCount())
					.executeUpdate() == 1));
			lost = true; // 의도한 반환이다. 늦은 시도는 DB/유료 호출을 더 하지 않는다.
			if (released) {
				log.info("job released");
			}
		}

		@Override
		public synchronized void close() {
			active = false;
			if (renewal != null) {
				renewal.cancel(false);
			}
		}
	}
}
