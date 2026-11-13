/*
 * Copyright (c) 2026-present Douglas Hoard
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.altcontainers.reaper;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Network;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous cleanup executor with retry, backoff, and escalation.
 *
 * <p>Owns two thread pools: a {@link ScheduledThreadPoolExecutor} for the
 * process ladder, and a cached daemon-thread pool for
 * {@link CompletableFuture#runAsync} Docker command execution. This
 * separation prevents deadlock when per-tier {@code Future.get(timeout)}
 * calls block the scheduled executor threads.
 *
 * <p>Package-private — not part of the public API.
 */
final class CleanupExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CleanupExecutor.class);

    /**
     * Scheduled executor core pool size. Each worker blocks on one Docker call at a time, so this
     * bounds how many removals are in flight; a session sweep may have hundreds of resources.
     */
    static final int POOL_SIZE = 8;

    /** Attempts before network force-disconnect activates. */
    static final int NETWORK_FORCE_THRESHOLD = 3;

    /** Base backoff delay in milliseconds. */
    static final long BACKOFF_BASE_MS = 1000L;

    /** Maximum backoff delay in milliseconds. */
    static final long BACKOFF_CAP_MS = 30_000L;

    /** Drain deadline in minutes for session sweep. */
    static final long DRAIN_DEADLINE_MINUTES = 5;

    private final DockerClient client;
    private final int maxAttempts;
    private final long destroyTimeoutMs;
    private final long forceTimeoutMs;
    private final ScheduledExecutorService scheduledExecutor;
    private final ExecutorService cachedDockerExecutor;
    private final Clock clock;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Map<String, CleanupTask> unresolved = new ConcurrentHashMap<>();

    /**
     * Creates a new cleanup executor.
     *
     * @param client the Docker client shared across all worker threads
     * @param maxAttempts the maximum number of attempts per resource before giving up
     * @param destroyTimeoutMs the Java-side timeout in milliseconds for the destroy tier
     * @param forceTimeoutMs the Java-side timeout in milliseconds for the force tier
     * @param scheduledExecutor the scheduled executor for the process ladder
     * @param clock the clock for backoff scheduling
     */
    CleanupExecutor(
            DockerClient client,
            int maxAttempts,
            long destroyTimeoutMs,
            long forceTimeoutMs,
            ScheduledExecutorService scheduledExecutor,
            Clock clock) {
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.destroyTimeoutMs = destroyTimeoutMs;
        this.forceTimeoutMs = forceTimeoutMs;
        this.scheduledExecutor = scheduledExecutor;
        this.clock = clock;
        this.cachedDockerExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "altcontainers-reaper-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Enqueues a task for immediate execution. Submissions are rejected once
     * {@link #shutdown()} has initiated the drain; backoff retries scheduled
     * from within {@link #process(CleanupTask)} are still accepted so that
     * {@code maxAttempts} is honored during the session-sweep drain.
     *
     * @param task the cleanup task
     */
    void submit(CleanupTask task) {
        if (draining.get()) {
            logger.debug("Drain in progress, dropping submission for {} {}", task.type(), task.id());
            return;
        }
        enqueue(task, "submission", () -> scheduledExecutor.execute(tracked(task)));
    }

    /**
     * Enqueues a task for delayed execution (retry after backoff).
     *
     * @param task the cleanup task
     * @param delayMs the delay in milliseconds before execution
     */
    void schedule(CleanupTask task, long delayMs) {
        enqueue(
                task,
                "scheduled retry",
                () -> scheduledExecutor.schedule(tracked(task), delayMs, TimeUnit.MILLISECONDS));
    }

    /**
     * Wraps a task so the pending count stays accurate whether the task
     * completes normally or throws.
     *
     * @param task the cleanup task
     * @return a runnable that processes the task and maintains the pending count
     */
    private Runnable tracked(CleanupTask task) {
        return () -> {
            try {
                process(task);
            } finally {
                pendingTasks.decrementAndGet();
            }
        };
    }

    /**
     * Runs a submission action, maintaining the pending count and logging a
     * dropped task when the executor has been shut down.
     *
     * @param task the cleanup task
     * @param action the action noun used in the drop log message
     * @param submission the submission action
     */
    private void enqueue(CleanupTask task, String action, Runnable submission) {
        pendingTasks.incrementAndGet();
        try {
            submission.run();
        } catch (RejectedExecutionException e) {
            pendingTasks.decrementAndGet();
            logger.debug("Executor shut down, dropping {} for {} {}", action, task.type(), task.id());
        }
    }

    /**
     * Initiates the drain: rejects new external submissions while allowing
     * in-flight and backoff-scheduled retries to complete. Does not stop the
     * thread pools; call {@link #shutdownNow()} to abort them.
     */
    void shutdown() {
        draining.set(true);
    }

    /**
     * Aborts both thread pools, cancelling queued and delayed tasks. Use after
     * the drain deadline expires.
     */
    void shutdownNow() {
        draining.set(true);
        scheduledExecutor.shutdownNow();
        cachedDockerExecutor.shutdownNow();
    }

    /**
     * Blocks until all submitted and retried tasks have completed or the
     * timeout expires, whichever comes first. May be called after
     * {@link #shutdown()} without the pools having been stopped.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return {@code true} if all tasks completed, {@code false} if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return awaitIdle(timeout, unit);
    }

    /**
     * Blocks until no submitted or retried task is pending, or the timeout expires. Unlike
     * {@link #awaitTermination(long, TimeUnit)} this is used between sweep phases, before
     * {@link #shutdown()}, so further submissions remain possible afterwards.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return {@code true} if no task is pending, {@code false} if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            if (pendingTasks.get() == 0) {
                return true;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            Thread.sleep(Math.min(remainingNanos / 1_000_000L, 200L));
        }
    }

    /**
     * Returns the number of tasks pending in the scheduled executor's work queue.
     *
     * @return the queue size
     */
    int getQueueSize() {
        if (scheduledExecutor instanceof ThreadPoolExecutor tpe) {
            return tpe.getQueue().size();
        }
        return -1;
    }

    /**
     * Processes a single cleanup task through the destroy → force → requeue ladder. A container
     * task flagged {@link CleanupTask#force()} skips the graceful destroy tier.
     *
     * @param task the cleanup task
     */
    void process(CleanupTask task) {
        boolean success;
        if (task.type() == CleanupTask.ResourceType.CONTAINER) {
            success = !task.force() && destroyContainer(task.id());
            if (!success) {
                success = forceRemoveContainer(task.id());
            }
        } else {
            success = removeNetwork(task.id());
            if (!success && task.attempts() >= NETWORK_FORCE_THRESHOLD) {
                success = forceRemoveNetwork(task.id());
            }
        }

        String key = key(task);
        if (success) {
            unresolved.remove(key);
            return;
        }
        CleanupTask next = task.withAttempts(task.attempts() + 1);
        if (next.attempts() < maxAttempts) {
            long delay = backoffMs(next.attempts());
            logger.debug(
                    "Requeuing {} {} (attempt {}/{}) with backoff {}ms",
                    task.type(),
                    task.id(),
                    next.attempts(),
                    maxAttempts,
                    delay);
            schedule(next, delay);
        } else {
            unresolved.put(key, next);
            logger.warn(
                    "gave up on {} {} after {} attempts in this sweep; the session sweep re-discovers it",
                    task.type(),
                    task.id(),
                    maxAttempts);
        }
    }

    /**
     * Returns the map key identifying a resource independently of its attempt count.
     *
     * @param task the cleanup task
     * @return the {@code type:id} key
     */
    private static String key(CleanupTask task) {
        return task.type() + ":" + task.id();
    }

    /**
     * Returns the number of resources whose attempt ladder was exhausted and that have not
     * been removed since.
     *
     * @return the unresolved resource count
     */
    int unresolvedCount() {
        return unresolved.size();
    }

    /**
     * Returns the number of tasks submitted or retried but not yet completed.
     *
     * @return the pending task count
     */
    int pendingCount() {
        return pendingTasks.get();
    }

    /**
     * Returns the ids of unresolved resources of the given type.
     *
     * @param type the resource type
     * @return the sorted resource ids
     */
    List<String> unresolvedIds(CleanupTask.ResourceType type) {
        return unresolved.values().stream()
                .filter(task -> task.type() == type)
                .map(CleanupTask::id)
                .sorted()
                .toList();
    }

    /**
     * Graceful stop + remove for a container.
     *
     * <p>The Docker {@code stopContainerCmd} uses {@code Reaper.STOP_TIMEOUT_MS}
     * as the daemon-side grace period. The Java-side timeout
     * ({@code destroyTimeoutMs}) wraps the entire stop+remove call via
     * {@code Future.get}. On {@code TimeoutException}, falls through
     * (the daemon-side operation may still be in-flight — accepted since
     * force-remove is idempotent and wins).
     *
     * <p>Anonymous volumes created by the container are removed with it; named volumes and
     * bind mounts are unaffected.
     *
     * @param id the container ID
     * @return {@code true} if the container was successfully stopped and removed
     */
    boolean destroyContainer(String id) {
        int stopTimeoutSec = stopTimeoutSeconds(Reaper.STOP_TIMEOUT_MS);
        return runTier(id, DESTROY_CONTAINER_LOG, destroyTimeoutMs, () -> {
            try {
                client.stopContainerCmd(id).withTimeout(stopTimeoutSec).exec();
            } catch (NotFoundException | NotModifiedException e) {
                // Best effort: container already stopped or removed — proceed to remove
            }
            client.removeContainerCmd(id).withRemoveVolumes(true).exec();
        });
    }

    /**
     * Force-removes a container, including the anonymous volumes it created.
     *
     * @param id the container ID
     * @return {@code true} if the container was successfully force-removed
     */
    boolean forceRemoveContainer(String id) {
        return runTier(
                id,
                FORCE_REMOVE_CONTAINER_LOG,
                forceTimeoutMs,
                () -> client.removeContainerCmd(id)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec());
    }

    /**
     * Removes a network.
     *
     * @param id the network ID
     * @return {@code true} if the network was successfully removed
     */
    boolean removeNetwork(String id) {
        return runTier(
                id,
                REMOVE_NETWORK_LOG,
                forceTimeoutMs,
                () -> client.removeNetworkCmd(id).exec());
    }

    /**
     * Force-removes a network by disconnecting all endpoints then removing.
     *
     * @param id the network ID
     * @return {@code true} if the network was successfully force-removed
     */
    boolean forceRemoveNetwork(String id) {
        return runTier(id, FORCE_REMOVE_NETWORK_LOG, forceTimeoutMs, () -> {
            Network network = client.inspectNetworkCmd().withNetworkId(id).exec();
            java.util.Map<String, Network.ContainerNetworkConfig> containers = network.getContainers();
            if (containers != null) {
                for (String containerId : containers.keySet()) {
                    try {
                        client.disconnectFromNetworkCmd()
                                .withContainerId(containerId)
                                .withNetworkId(id)
                                .withForce(true)
                                .exec();
                    } catch (RuntimeException e) {
                        logger.warn(
                                "Failed to disconnect container {} from network {}: {}",
                                containerId,
                                id,
                                e.getMessage());
                    }
                }
            }
            client.removeNetworkCmd(id).exec();
        });
    }

    /**
     * Runs one tier of the cleanup ladder: a Docker call bounded by a Java-side
     * timeout, with the shared cause-to-outcome mapping. Absent or already
     * removed resources count as success; timeouts, interrupts, and rejections
     * count as failure.
     *
     * @param id the resource ID
     * @param log the tier's log wording
     * @param timeoutMs the Java-side timeout in milliseconds
     * @param call the Docker call to run
     * @return {@code true} if the resource is gone after this tier
     */
    private boolean runTier(String id, TierLog log, long timeoutMs, Runnable call) {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(call, cachedDockerExecutor);
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            logger.info(log.success(), id);
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                logger.debug(log.notFound(), id);
                return true;
            }
            if (cause instanceof NotModifiedException) {
                logger.debug(log.notModified(), id);
                return true;
            }
            if (isRemovalInProgress(cause)) {
                logger.debug(log.inProgress(), id);
                return true;
            }
            logger.warn(log.failed(), id, cause.getMessage());
            return false;
        } catch (TimeoutException e) {
            logger.debug(log.timedOut(), id, timeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug(log.interrupted(), id);
            return false;
        } catch (RejectedExecutionException e) {
            logger.debug(log.dropped(), id);
            return false;
        }
    }

    /**
     * Returns whether a Docker failure reports that removal of the resource is already in
     * progress (HTTP 409). The daemon is already removing it, so the tier counts as successful
     * instead of consuming a retry attempt.
     *
     * @param cause the failure cause
     * @return {@code true} for a 409 "removal ... is already in progress" conflict
     */
    static boolean isRemovalInProgress(Throwable cause) {
        return cause instanceof ConflictException
                && cause.getMessage() != null
                && cause.getMessage().contains("is already in progress");
    }

    /**
     * Log wording for one cleanup tier, kept per tier so every message is
     * emitted verbatim.
     *
     * @param success the success message
     * @param notFound the not-found (treated as success) message
     * @param notModified the not-modified (treated as success) message
     * @param inProgress the removal-already-in-progress (treated as success) message
     * @param failed the failure warning
     * @param timedOut the timeout message
     * @param interrupted the interrupt message
     * @param dropped the executor-rejected message
     */
    private record TierLog(
            String success,
            String notFound,
            String notModified,
            String inProgress,
            String failed,
            String timedOut,
            String interrupted,
            String dropped) {}

    private static final TierLog DESTROY_CONTAINER_LOG = new TierLog(
            "Removed container {}",
            "Container {} not found during destroy (already removed)",
            "Container {} already stopped (Status 304)",
            "Container {} removal already in progress (Status 409)",
            "Failed to destroy container {}: {}",
            "Destroy timed out for container {} after {}ms",
            "Interrupted during destroy of container {}",
            "Executor shut down, dropping destroy for container {}");

    private static final TierLog FORCE_REMOVE_CONTAINER_LOG = new TierLog(
            "Force-removed container {}",
            "Container {} not found during force-remove (already removed)",
            "Container {} already removed (Status 304)",
            "Container {} removal already in progress (Status 409)",
            "Failed to force-remove container {}: {}",
            "Force-remove timed out for container {} after {}ms",
            "Interrupted during force-remove of container {}",
            "Executor shut down, dropping force-remove for container {}");

    private static final TierLog REMOVE_NETWORK_LOG = new TierLog(
            "Removed network {}",
            "Network {} not found (already removed)",
            "Network {} already removed (Status 304)",
            "Network {} removal already in progress (Status 409)",
            "Failed to remove network {}: {}",
            "Remove timed out for network {} after {}ms",
            "Interrupted during remove of network {}",
            "Executor shut down, dropping remove for network {}");

    private static final TierLog FORCE_REMOVE_NETWORK_LOG = new TierLog(
            "Force-removed network {}",
            "Network {} not found during force-remove (already removed)",
            "Network {} already removed (Status 304)",
            "Network {} removal already in progress (Status 409)",
            "Failed to force-remove network {}: {}",
            "Force-remove timed out for network {} after {}ms",
            "Interrupted during force-remove of network {}",
            "Executor shut down, dropping force-remove for network {}");

    /**
     * Converts the configured stop timeout in milliseconds to whole seconds
     * for the Docker stop API, with a floor of 1s so a sub-second
     * configuration never produces a zero timeout.
     *
     * @param stopTimeoutMs the configured stop timeout in milliseconds
     * @return the timeout in seconds, always {@code >= 1}
     */
    static int stopTimeoutSeconds(int stopTimeoutMs) {
        return Math.max(1, stopTimeoutMs / 1000);
    }

    /**
     * Computes the backoff delay for the given attempt count.
     *
     * @param attempts the post-increment attempt count (1-based)
     * @return the backoff delay in milliseconds
     */
    static long backoffMs(int attempts) {
        // Bound the shift exponent: a large configured attempt count would otherwise
        // overflow the shift and produce a short (even negative) delay, retrying
        // immediately instead of backing off. The cap is reached long before this bound.
        int exponent = Math.min(Math.max(attempts, 0), 16);
        return Math.min(BACKOFF_BASE_MS << exponent, BACKOFF_CAP_MS);
    }
}
