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

package nonapi.org.altcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import nonapi.org.altcontainers.reaper.ResourceController;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.WaitCondition;

/**
 * Public lifecycle facade for creating, starting, and destroying Docker containers.
 *
 * <p>{@code ContainerManager} is the primary entry point for container lifecycle operations. It
 * orchestrates image pulling, container creation, startup, wait-for-readiness, and cleanup. The
 * singleton delegates to {@link DockerClient} for all Docker daemon communication.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
 *         .exposePorts(8080)
 *         .waitForContainerPort(8080)
 *         .build();
 * try (Container container = ContainerManager.getInstance().createContainer(spec)) {
 *     // ... use container ...
 * }
 * }</pre>
 *
 * <p>Explicit cleanup is also supported:
 *
 * <pre>{@code
 * Container container = ContainerManager.getInstance().createContainer(spec);
 * try {
 *     // ... use container ...
 * } finally {
 *     ContainerManager.getInstance().destroyContainer(container);
 * }
 * }</pre>
 */
public final class ContainerManager {

    /**
     * Poll interval used while waiting for readiness conditions.
     */
    private static final long READINESS_POLL_INTERVAL_MILLIS = 250L;

    /**
     * Random jitter (&#177;milliseconds) added to each poll interval to prevent thundering-herd on Docker daemon.
     */
    private static final long JITTER_MILLISECONDS = 50L;

    /**
     * Base for the linear retry backoff between startup attempts.
     */
    private static final long RETRY_BACKOFF_BASE_MILLISECONDS = 1000L;

    /**
     * The singleton instance, backed by Docker.
     */
    private static final ContainerManager INSTANCE = new ContainerManager(DockerClient.instance());

    /**
     * The Docker execution backend.
     */
    private final DockerClient dockerClient;

    /**
     * Creates a container manager backed by the given Docker client.
     *
     * @param dockerClient the Docker client; must not be {@code null}
     */
    private ContainerManager(DockerClient dockerClient) {
        this.dockerClient = Objects.requireNonNull(dockerClient);
    }

    /**
     * Returns the shared singleton {@link ContainerManager}.
     *
     * @return the singleton container manager instance
     */
    public static ContainerManager getInstance() {
        return INSTANCE;
    }

    /**
     * Creates, starts, and waits for a container to become ready, returning a runtime handle.
     *
     * <p>This method orchestrates the full container lifecycle: it validates the specification, pulls the
     * image if missing, creates the Docker container, starts it, attaches a log stream, and waits for all
     * configured readiness conditions to be satisfied. On failure, it cleans up any partially created
     * container and retries up to the configured number of startup attempts with linear backoff.
     *
     * <p>The returned {@link Container} is ready to use. It must be released via
     * {@link #destroyContainer(Container)} or {@link Container#close()}.
     *
     * @param containerSpec the immutable desired container configuration; must not be {@code null}
     * @return a handle to the started, ready container
     * @throws IllegalArgumentException if {@code startupAttempts} is {@code < 1} or
     *     {@code startupTimeout} is not positive
     * @throws ContainerException if the container cannot be pulled, started, or reach a ready state
     */
    public Container createContainer(ContainerSpec containerSpec) {
        Objects.requireNonNull(containerSpec, "containerSpec");

        int startupAttempts = containerSpec.startupAttempts();
        Duration startupTimeout = containerSpec.startupTimeout();

        if (startupAttempts < 1) {
            throw new IllegalArgumentException("startupAttempts must be >= 1, was " + startupAttempts);
        }
        if (startupTimeout == null
                || startupTimeout.isZero()
                || startupTimeout.isNegative()
                || !isNanosRepresentable(startupTimeout)) {
            throw new IllegalArgumentException("startupTimeout must be positive, was " + startupTimeout);
        }

        String image = containerSpec.image();

        var controller = ResourceController.instance();
        var session = controller.ensureReady();
        var labels = session.labelsForNewResource();

        dockerClient.pullImageIfMissing(image);

        ContainerCreateSpec createSpec = new ContainerCreateSpec(
                containerSpec.image(),
                containerSpec.command(),
                containerSpec.exposedPorts(),
                containerSpec.bindMounts(),
                containerSpec.networkMode(),
                containerSpec.networkAliases(),
                containerSpec.workingDirectory(),
                containerSpec.ulimits(),
                containerSpec.memory(),
                containerSpec.memorySwap(),
                containerSpec.shmSize(),
                containerSpec.cpuShares(),
                containerSpec.cpuPeriod(),
                containerSpec.cpuQuota(),
                labels);

        ContainerException lastException = null;
        for (int attempt = 0; attempt < startupAttempts; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_BASE_MILLISECONDS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ContainerException("Interrupted during retry backoff", e);
                }
            }

            // Fresh wait-condition state for this attempt.
            List<WaitCondition> attemptWaitConditions = createAttemptWaitConditions(containerSpec.waitConditions());
            Consumer<String> rawLineConsumer = line -> dispatchRawLogLine(attemptWaitConditions, line);

            String containerId = null;
            try {
                containerId = dockerClient.createContainer(createSpec);
                dockerClient.startContainer(containerId);
                // The returned LogStreamHandle is intentionally ignored: DockerClient owns and closes
                // the handle when the container is destroyed.
                Consumer<String> displayConsumer =
                        containerSpec.logConsumer() != null ? containerSpec.logConsumer() : line -> {};
                dockerClient.attachLogStream(containerId, displayConsumer, rawLineConsumer);
                waitUntilReady(containerId, image, startupTimeout, attemptWaitConditions);
                return new Container(containerId, image);
            } catch (ContainerException e) {
                lastException = e;
                destroyContainerAfterFailure(containerId, e);
            } catch (RuntimeException e) {
                ContainerException wrapped = new ContainerException("Failed to start container for image: " + image, e);
                destroyContainerAfterFailure(containerId, wrapped);
                throw wrapped;
            }
        }
        // startupAttempts >= 1 guarantees lastException is non-null when we reach here.
        assert lastException != null : "lastException must be non-null after at least one attempt";
        throw lastException;
    }

    /**
     * Stops and removes a container, blocking until Docker confirms it is gone.
     *
     * <p>Idempotent: destroying an already-destroyed container is safe. Passing {@code null} is a
     * no-op. The container cannot be reused after this call.
     *
     * @param container the container to destroy, or {@code null} for a no-op
     * @throws ContainerException if the container is not confirmed gone within the destroy deadline
     */
    public void destroyContainer(Container container) {
        if (container == null) {
            return;
        }
        dockerClient.destroyContainer(container.id());
    }

    /**
     * Creates fresh wait-condition instances for a single startup attempt. Each condition carries
     * independent state (e.g., log-match counters) so that stale log lines from prior attempts cannot
     * satisfy a new attempt.
     *
     * @param conditions the configured wait conditions from the spec
     * @return a new list of fresh wait conditions
     */
    private static List<WaitCondition> createAttemptWaitConditions(List<WaitCondition> conditions) {
        List<WaitCondition> result = new ArrayList<>();
        for (WaitCondition condition : conditions) {
            result.add(condition.newAttemptCondition());
        }
        return result;
    }

    /**
     * Dispatches a raw (newline-terminated) log line to any matching {@link WaitCondition.LogWait}
     * conditions in the given attempt-specific list.
     *
     * @param attemptWaitConditions the current attempt's wait-condition list
     * @param rawLine the raw log line including its trailing newline
     */
    private static void dispatchRawLogLine(List<WaitCondition> attemptWaitConditions, String rawLine) {
        for (WaitCondition condition : attemptWaitConditions) {
            if (condition instanceof WaitCondition.LogWait logWait) {
                logWait.incrementIfMatches(rawLine);
            }
        }
    }

    /**
     * Blocks until all wait conditions are satisfied, or until the startup timeout elapses.
     *
     * <p>Conditions are removed from the pending set once satisfied — a port that opens or a log line
     * that has been seen the required number of times will never be rechecked. Timeout uses monotonic
     * {@link System#nanoTime()} to avoid wall-clock drift.
     *
     * @param containerId the container identifier
     * @param image the Docker image name
     * @param startupTimeout the readiness timeout
     * @param attemptWaitConditions the current attempt's wait-condition list
     * @throws ContainerException if the container is not ready within the startup timeout, or if the
     *     calling thread is interrupted while waiting
     */
    private void waitUntilReady(
            String containerId, String image, Duration startupTimeout, List<WaitCondition> attemptWaitConditions) {
        Container container = new Container(containerId, image);
        // mutable copy; satisfied conditions are removed to avoid redundant probes
        List<WaitCondition> pending = new ArrayList<>(attemptWaitConditions);
        long deadlineNanos = System.nanoTime() + startupTimeout.toNanos();
        while (!pending.isEmpty()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            pending.removeIf(condition -> condition.check(container));
            if (pending.isEmpty()) {
                return;
            }
            try {
                long jitter = ThreadLocalRandom.current().nextLong(-JITTER_MILLISECONDS, JITTER_MILLISECONDS + 1);
                long sleepNanos = Math.max(0, (READINESS_POLL_INTERVAL_MILLIS + jitter) * 1_000_000L);
                sleepNanos = Math.min(sleepNanos, remainingNanos);
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for container: " + containerId, e);
            }
        }
        if (!pending.isEmpty()) {
            long timeoutMillis = startupTimeout.toMillis();
            String timeoutMsg = timeoutMillis >= 1000 ? startupTimeout.toSeconds() + "s" : timeoutMillis + "ms";
            String pendingConditions = pending.stream()
                    .map(condition -> condition.getClass().getSimpleName())
                    .collect(Collectors.joining(", "));
            throw new ContainerException("Container " + containerId + " not ready after " + timeoutMsg
                    + "; pending conditions: " + pendingConditions);
        }
    }

    /**
     * Destroys a partially created container after a startup failure, adding any cleanup failure as a
     * suppressed exception on the primary failure so that the primary cause is not masked.
     *
     * @param containerId the container identifier, or {@code null} if none was created
     * @param primaryFailure the primary failure that triggered cleanup
     */
    private void destroyContainerAfterFailure(String containerId, RuntimeException primaryFailure) {
        if (containerId != null) {
            try {
                dockerClient.destroyContainer(containerId);
            } catch (RuntimeException cleanupFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Returns whether the given duration can be represented exactly in nanoseconds without overflow.
     *
     * @param duration the duration to check
     * @return {@code true} if the duration is nanos-representable
     */
    @SuppressWarnings("PMD.UselessPureMethodCall")
    private static boolean isNanosRepresentable(Duration duration) {
        try {
            duration.toNanos();
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }
}
