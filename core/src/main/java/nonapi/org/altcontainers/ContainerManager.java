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

import static nonapi.org.altcontainers.ContainerOperations.*;
import static nonapi.org.altcontainers.ImageOperations.*;

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
import org.altcontainers.api.WaitStrategy;

/**
 * Public lifecycle facade for creating, starting, and destroying Docker containers.
 *
 * <p>{@code ContainerManager} is the primary entry point for container lifecycle operations. It
 * orchestrates image pulling, container creation, startup, wait-for-readiness, and cleanup. The
 * singleton delegates to static operation utilities backed by {@link DockerClient} for all Docker daemon communication.
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
     * Maximum time to wait for Docker to propagate port mappings after
     * {@code startContainer} before invoking the startup consumer.
     */
    private static final Duration PORT_MAPPING_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The singleton instance, backed by Docker.
     */
    private static final ContainerManager INSTANCE = new ContainerManager(DockerClient.instance());

    /**
     * The Docker execution backend.
     */
    private final DockerClient client;

    /**
     * Creates a container manager backed by the given Docker client.
     *
     * @param client the Docker client; must not be {@code null}
     */
    private ContainerManager(DockerClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
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
     * configured readiness conditions to be satisfied.
     *
     * <p>On {@link ContainerException} (transient Docker or container failures), this method cleans up any
     * partially created container and retries up to the configured number of startup attempts with linear
     * backoff. {@link RuntimeException} thrown by the
     * {@link ContainerSpec#startupConsumer()} is wrapped in {@link ContainerException} and follows
     * the same retry-and-destroy behavior. All other unexpected {@link RuntimeException} (e.g.,
     * programming errors in framework-level operations) is wrapped in {@link ContainerException} and
     * thrown immediately without retry.
     *
     * <p>The returned {@link Container} is ready to use. It must be released via
     * {@link #destroyContainer(Container)} or {@link Container#close()}.
     *
     * @param containerSpec the immutable desired container configuration; must not be {@code null}
     * @return a handle to the started, ready container
     * @throws IllegalArgumentException if {@code startupAttempts} is {@code < 1},
     *     {@code startupTimeout} is {@code null}, zero, negative, or not exactly representable in nanoseconds
     * @throws ContainerException if the container cannot be pulled, started, or reach a ready state
     */
    public Container createContainer(ContainerSpec containerSpec) {
        Objects.requireNonNull(containerSpec, "containerSpec must not be null");

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

        pullImageIfMissing(client, image);

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
                labels,
                containerSpec.environment(),
                containerSpec.portBindings());

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

            // Fresh wait-strategy state for this attempt.
            AttemptState attemptState = createAttemptState(containerSpec.waitConditions());
            Consumer<String> rawLineConsumer = line -> dispatchRawLogLine(attemptState, line);

            String containerId = null;
            try {
                containerId = ContainerOperations.createContainer(client, createSpec);
                startContainer(client, containerId);
                // The returned LogStreamHandle is intentionally ignored: DockerClient owns and closes
                // the handle when the container is destroyed.
                Consumer<String> logConsumerRef = containerSpec.logConsumer();
                Consumer<String> displayConsumer = logConsumerRef != null ? logConsumerRef : line -> {};
                client.attachLogStream(containerId, displayConsumer, rawLineConsumer);

                awaitPortMappings(client, containerId, containerSpec.exposedPorts(), PORT_MAPPING_TIMEOUT);

                Container container = new Container(containerId, image);
                try {
                    containerSpec.startupConsumer().accept(container);
                } catch (ContainerException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new ContainerException("startupConsumer failed for container " + containerId, e);
                }

                waitUntilReady(containerId, image, startupTimeout, attemptState.strategies());
                return container;
            } catch (ContainerException caught) {
                ContainerException e = caught;
                if (containerId != null) {
                    String diagnostics = inspectContainerDiagnostics(client, containerId);
                    if (!diagnostics.isEmpty()) {
                        ContainerException enriched = new ContainerException(
                                caught.getMessage() + " [" + diagnostics + "]", caught.getCause());
                        enriched.setStackTrace(caught.getStackTrace());
                        e = enriched;
                    }
                }
                lastException = e;
                destroyContainerAfterFailure(containerId, e);
            } catch (RuntimeException e) {
                String message = "Failed to start container for image: " + image;
                if (containerId != null) {
                    String diagnostics = inspectContainerDiagnostics(client, containerId);
                    if (!diagnostics.isEmpty()) {
                        message = message + " [" + diagnostics + "]";
                    }
                }
                ContainerException wrapped = new ContainerException(message, e);
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
        client.destroyContainer(container.id());
    }

    /**
     * Resolved state for a single startup attempt: the strategy list and the
     * pre-filtered log-line consumers. Computed once per attempt to avoid
     * repeatedly calling {@link WaitStrategy#logLineConsumer()} on every log
     * line.
     */
    private record AttemptState(List<WaitStrategy> strategies, List<Consumer<String>> logConsumers) {}

    /**
     * Creates fresh wait-strategy instances for a single startup attempt. Each strategy carries
     * independent state (e.g., log-match counters) so that stale log lines from prior attempts cannot
     * satisfy a new attempt. Log-line consumers are collected and pre-filtered so the dispatch path
     * iterates only the non-null consumers.
     *
     * @param strategies the configured wait strategies from the spec
     * @return the resolved attempt state
     */
    private static AttemptState createAttemptState(List<WaitStrategy> strategies) {
        List<WaitStrategy> fresh = new ArrayList<>();
        List<Consumer<String>> logConsumers = new ArrayList<>();
        for (WaitStrategy strategy : strategies) {
            WaitStrategy copy = strategy.newAttemptCondition();
            fresh.add(copy);
            Consumer<String> consumer = copy.logLineConsumer();
            if (consumer != null) {
                logConsumers.add(consumer);
            }
        }
        return new AttemptState(fresh, List.copyOf(logConsumers));
    }

    /**
     * Dispatches a raw (newline-terminated) log line to all pre-computed log-line consumers in the
     * given attempt state.
     *
     * @param attemptState the current attempt's resolved state
     * @param rawLine the raw log line including its trailing newline
     */
    private static void dispatchRawLogLine(AttemptState attemptState, String rawLine) {
        for (Consumer<String> consumer : attemptState.logConsumers()) {
            consumer.accept(rawLine);
        }
    }

    /**
     * Blocks until all wait conditions are satisfied, or until the startup timeout elapses.
     *
     * <p>Delegates to {@link #awaitStrategies(Container, Duration, List)}, which re-checks every
     * pending strategy at the top of each iteration — including the iteration in which the deadline
     * expires — so that a strategy satisfied asynchronously during a sleep interval is always
     * observed before a timeout is reported.
     *
     * @param containerId the container identifier
     * @param image the Docker image name
     * @param startupTimeout the readiness timeout
     * @param attemptStrategies the current attempt's strategy list
     * @throws ContainerException if the container is not ready within the startup timeout, or if the
     *     calling thread is interrupted while waiting
     */
    private void waitUntilReady(
            String containerId, String image, Duration startupTimeout, List<WaitStrategy> attemptStrategies) {
        Container container = new Container(containerId, image);
        awaitStrategies(container, startupTimeout, attemptStrategies);
    }

    /**
     * Blocks until all {@code strategies} are satisfied against {@code container}, or until
     * {@code startupTimeout} elapses.
     *
     * <p>Strategies are re-checked with {@code removeIf} at the top of every iteration, before the
     * deadline check. This guarantees that a strategy satisfied asynchronously (for example a
     * {@code LogWaitStrategy} whose counter is incremented by the log-stream daemon thread during a
     * sleep interval) is observed even on the iteration in which the deadline expires, so a ready
     * container is never falsely reported as timed out.
     *
     * <p>Timeout uses monotonic {@link System#nanoTime()} to avoid wall-clock drift.
     *
     * @param container the container handle passed to each strategy's {@link WaitStrategy#check}
     * @param startupTimeout the readiness timeout; must be positive
     * @param strategies the wait strategies to satisfy; copied internally and not modified thereafter
     * @throws ContainerException if any strategy remains unsatisfied after {@code startupTimeout}, or
     *     if the calling thread is interrupted while waiting
     */
    static void awaitStrategies(Container container, Duration startupTimeout, List<WaitStrategy> strategies) {
        // mutable copy; satisfied strategies are removed to avoid redundant probes
        List<WaitStrategy> pending = new ArrayList<>(strategies);
        long deadlineNanos = System.nanoTime() + startupTimeout.toNanos();
        while (!pending.isEmpty()) {
            // Re-check strategies FIRST so an async-satisfied strategy is observed even when the
            // deadline elapsed during the previous sleep (see Finding 9).
            pending.removeIf(condition -> condition.check(container));
            if (pending.isEmpty()) {
                return;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos < 1_000_000L) {
                // Less than 1 ms remaining: report timeout rather than spinning.
                // Strategies were already re-checked via removeIf at the top of
                // this iteration, so an async-satisfied strategy was observed.
                break;
            }
            try {
                long jitter = ThreadLocalRandom.current().nextLong(-JITTER_MILLISECONDS, JITTER_MILLISECONDS + 1);
                long sleepNanos = Math.max(0, (READINESS_POLL_INTERVAL_MILLIS + jitter) * 1_000_000L);
                sleepNanos = Math.min(sleepNanos, remainingNanos);
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for container: " + container.id(), e);
            }
        }
        if (!pending.isEmpty()) {
            String timeoutMsg = formatStartupTimeout(startupTimeout);
            String pendingConditions = pending.stream()
                    .map(strategy -> strategy.getClass().getSimpleName())
                    .collect(Collectors.joining(", "));
            throw new ContainerException("Container " + container.id() + " not ready after " + timeoutMsg
                    + "; pending strategies: " + pendingConditions);
        }
    }

    /**
     * Formats the readiness timeout for diagnostic messages, preserving sub-second precision.
     *
     * <p>Whole-second durations render as {@code "<s>s"}; every other duration renders as
     * {@code "<ms>ms"}. This avoids the truncation in {@link Duration#toSeconds()} that previously
     * reported a 1500 ms timeout as {@code "1s"}.
     *
     * @param startupTimeout the timeout to format
     * @return a human-readable timeout string
     */
    static String formatStartupTimeout(Duration startupTimeout) {
        long timeoutMillis = startupTimeout.toMillis();
        return (timeoutMillis >= 1000 && timeoutMillis % 1000 == 0)
                ? startupTimeout.toSeconds() + "s"
                : timeoutMillis + "ms";
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
                client.destroyContainer(containerId);
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
