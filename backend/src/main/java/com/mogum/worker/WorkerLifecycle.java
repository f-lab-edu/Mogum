package com.mogum.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.context.SmartLifecycle;

import com.mogum.config.WorkerProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 서버 수명에 실행기를 연결한다. 각 슬롯은 한 작업만 집으며 작업 분배 상태는 공유하지 않는다. */
@RequiredArgsConstructor
@Slf4j
public final class WorkerLifecycle implements SmartLifecycle {
	private final WorkerProperties workerProperties;
	private final Function<ScheduledExecutorService, JobRunner> runnerFactory;
	private final List<Slot> slots = new ArrayList<>();
	private volatile boolean running;
	private CompletableFuture<Void> shutdown = CompletableFuture.completedFuture(null);

	@Override
	public synchronized void start() {
		if (running || workerProperties.count() == 0) {
			return;
		}

		shutdown.join();
		slots.clear();
		for (int index = 0; index < workerProperties.count(); index++) {
			slots.add(new Slot(index));
		}

		running = true;
		slots.forEach(slot -> slot.thread.start());
	}

	@Override
	public void stop() {
		var completed = new CompletableFuture<Void>();
		stop(() -> completed.complete(null));
		completed.join();
	}

	@Override
	public synchronized void stop(Runnable callback) {
		if (running) {
			running = false;
			long deadline = System.nanoTime() + workerProperties.shutdownWait().toNanos();
			slots.forEach(slot -> {
				slot.stopping = true;
				slot.runner.stopClaiming();
				slot.wakeup.countDown();
			});

			// 모두 같은 종료 시각을 쓴다. 반환도 나란히 해 N배의 종료 대기가 생기지 않는다.
			shutdown = CompletableFuture.allOf(slots.stream()
				.map(slot -> slot.stopAt(deadline))
				.toArray(CompletableFuture[]::new));
		}

		shutdown.thenRun(callback);
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** Spring 7의 lifecycleProcessor.onClose는 singleton 파괴(Hikari.close)보다 먼저 실행된다. */
	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}

	private final class Slot {
		private final ScheduledExecutorService renewals;
		private final JobRunner runner;
		private final Thread thread;
		private final CountDownLatch wakeup = new CountDownLatch(1);
		private volatile boolean stopping;

		private Slot(int index) {
			renewals = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform()
					.daemon()
					.name("mogum-renewal-" + index)
					.factory());
			runner = runnerFactory.apply(renewals);

			// 한도 뒤 외부 호출이 늦게 끝나도 JVM 종료를 붙잡지 않는다.
			thread = Thread.ofPlatform()
				.daemon()
				.name("mogum-worker-" + index)
				.unstarted(this::poll);
		}

		private void poll() {
			Class<?> lastFailure = null;

			while (!stopping) {
				boolean found = false;
				try {
					found = runner.runOnce();
					lastFailure = null;
				} catch (RuntimeException exception) {
					report(exception, exception.getClass() != lastFailure);
					lastFailure = exception.getClass();
				}

				if (!found && !stopping) {
					try {
						if (wakeup.await(workerProperties.pollInterval().toNanos(), TimeUnit.NANOSECONDS)) {
							break;
						}
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		}

		private CompletableFuture<Void> stopAt(long deadline) {
			var stopped = new CompletableFuture<Void>();
			Thread.ofPlatform()
				.daemon()
				.name(thread.getName() + "-stop")
				.start(() -> {
					try {
						long remaining = deadline - System.nanoTime();
						if (remaining > 0) {
							TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
						}
						runner.releaseCurrent();
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						runner.releaseCurrent();
					} catch (RuntimeException exception) {
						report(exception, true);
					} finally {
						renewals.shutdownNow();
						stopped.complete(null);
					}
				});
			return stopped;
		}
	}

	private static void report(RuntimeException exception, boolean stack) {
		if (stack) {
			// 원문을 담을 수 있는 message/cause/suppressed는 전달하지 않는다.
			var safe = new RuntimeException(exception.getClass().getName());
			safe.setStackTrace(exception.getStackTrace());
			log.error("worker poll failed", safe);
		} else {
			log.error("worker poll failed");
		}
	}
}
