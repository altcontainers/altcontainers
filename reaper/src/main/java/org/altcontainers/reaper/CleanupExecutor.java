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
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Network;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Scheduled executor core pool size. */
    static final int POOL_SIZE = 2;

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
     * Enqueues a task for immediate execution.
     *
     * @param task the cleanup task
     */
    void submit(CleanupTask task) {
        try {
            scheduledExecutor.execute(() -> process(task));
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, dropping submission for {} {}", task.type(), task.id());
        }
    }

    /**
     * Enqueues a task for delayed execution (retry after backoff).
     *
     * @param task the cleanup task
     * @param delayMs the delay in milliseconds before execution
     */
    void schedule(CleanupTask task, long delayMs) {
        try {
            scheduledExecutor.schedule(() -> process(task), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, dropping scheduled retry for {} {}", task.type(), task.id());
        }
    }

    /**
     * Initiates graceful shutdown of both thread pools.
     *
     * <p>This method only initiates shutdown — it does not call
     * {@code awaitTermination} or {@code shutdownNow()}. The drain
     * deadline is enforced by the caller via {@link #awaitTermination}.
     */
    void shutdown() {
        scheduledExecutor.shutdown();
        cachedDockerExecutor.shutdown();
    }

    /**
     * Blocks until all tasks have completed or the timeout expires,
     * whichever comes first.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return {@code true} if the executor terminated, {@code false} if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return scheduledExecutor.awaitTermination(timeout, unit);
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
     * Processes a single cleanup task through the destroy → force → requeue ladder.
     *
     * @param task the cleanup task
     */
    void process(CleanupTask task) {
        boolean success;
        if (task.type() == CleanupTask.ResourceType.CONTAINER) {
            success = destroyContainer(task.id());
            if (!success) {
                success = forceRemoveContainer(task.id());
            }
        } else {
            success = removeNetwork(task.id());
            if (!success && task.attempts() >= NETWORK_FORCE_THRESHOLD) {
                success = forceRemoveNetwork(task.id());
            }
        }

        if (!success) {
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
                logger.warn(
                        "gave up on {} {} after {} attempts; remains labeled for manual cleanup",
                        task.type(),
                        task.id(),
                        maxAttempts);
            }
        }
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
     * @param id the container ID
     * @return {@code true} if the container was successfully stopped and removed
     */
    boolean destroyContainer(String id) {
        try {
            int stopTimeoutSec = (int) (Reaper.STOP_TIMEOUT_MS / 1000L);
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> {
                        try {
                            client.stopContainerCmd(id)
                                    .withTimeout(stopTimeoutSec)
                                    .exec();
                        } catch (NotFoundException | NotModifiedException e) {
                            // Best effort: container already stopped or removed — proceed to remove
                        }
                        client.removeContainerCmd(id).exec();
                    },
                    cachedDockerExecutor);
            future.get(destroyTimeoutMs, TimeUnit.MILLISECONDS);
            logger.info("Removed container {}", id);
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                logger.debug("Container {} not found during destroy (already removed)", id);
                return true;
            }
            if (cause instanceof NotModifiedException) {
                logger.debug("Container {} already stopped (Status 304)", id);
                return true;
            }
            logger.warn("Failed to destroy container {}: {}", id, cause.getMessage());
            return false;
        } catch (TimeoutException e) {
            logger.debug("Destroy timed out for container {} after {}ms", id, destroyTimeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted during destroy of container {}", id);
            return false;
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, dropping destroy for container {}", id);
            return false;
        }
    }

    /**
     * Force-removes a container.
     *
     * @param id the container ID
     * @return {@code true} if the container was successfully force-removed
     */
    boolean forceRemoveContainer(String id) {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> client.removeContainerCmd(id).withForce(true).exec(), cachedDockerExecutor);
            future.get(forceTimeoutMs, TimeUnit.MILLISECONDS);
            logger.info("Force-removed container {}", id);
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                logger.debug("Container {} not found during force-remove (already removed)", id);
                return true;
            }
            if (cause instanceof NotModifiedException) {
                logger.debug("Container {} already removed (Status 304)", id);
                return true;
            }
            logger.warn("Failed to force-remove container {}: {}", id, cause.getMessage());
            return false;
        } catch (TimeoutException e) {
            logger.debug("Force-remove timed out for container {} after {}ms", id, forceTimeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted during force-remove of container {}", id);
            return false;
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, dropping force-remove for container {}", id);
            return false;
        }
    }

    /**
     * Removes a network.
     *
     * @param id the network ID
     * @return {@code true} if the network was successfully removed
     */
    boolean removeNetwork(String id) {
        try {
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(() -> client.removeNetworkCmd(id).exec(), cachedDockerExecutor);
            future.get(forceTimeoutMs, TimeUnit.MILLISECONDS);
            logger.info("Removed network {}", id);
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                logger.debug("Network {} not found (already removed)", id);
                return true;
            }
            if (cause instanceof NotModifiedException) {
                logger.debug("Network {} already removed (Status 304)", id);
                return true;
            }
            logger.warn("Failed to remove network {}: {}", id, cause.getMessage());
            return false;
        } catch (TimeoutException e) {
            logger.debug("Remove timed out for network {} after {}ms", id, forceTimeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted during remove of network {}", id);
            return false;
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, dropping remove for network {}", id);
            return false;
        }
    }

    /**
     * Force-removes a network by disconnecting all endpoints then removing.
     *
     * @param id the network ID
     * @return {@code true} if the network was successfully force-removed
     */
    boolean forceRemoveNetwork(String id) {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> {
                        Network network =
                                client.inspectNetworkCmd().withNetworkId(id).exec();
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
                    },
                    cachedDockerExecutor);
            future.get(forceTimeoutMs, TimeUnit.MILLISECONDS);
            logger.info("Force-removed network {}", id);
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                logger.debug("Network {} not found during force-remove (already removed)", id);
                return true;
            }
            if (cause instanceof NotModifiedException) {
                logger.debug("Network {} already removed (Status 304)", id);
                return true;
            }
            logger.warn("Failed to force-remove network {}: {}", id, cause.getMessage());
            return false;
        } catch (TimeoutException e) {
            logger.debug("Force-remove timed out for network {} after {}ms", id, forceTimeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted during force-remove of network {}", id);
            return false;
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, dropping force-remove for network {}", id);
            return false;
        }
    }

    /**
     * Computes the backoff delay for the given attempt count.
     *
     * @param attempts the post-increment attempt count (1-based)
     * @return the backoff delay in milliseconds
     */
    static long backoffMs(int attempts) {
        return Math.min(BACKOFF_BASE_MS * (1L << attempts), BACKOFF_CAP_MS);
    }
}
