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

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import nonapi.org.altcontainers.docker.DockerEndpoint;
import nonapi.org.altcontainers.docker.DockerHttpClient;
import org.altcontainers.api.ContainerException;

/**
 * The single, process-wide gateway for all Docker interaction.
 *
 * <p>{@code DockerClient} owns the low-level Docker HTTP client, the log-stream tracking map,
 * and cross-cutting composite operations that coordinate focused utility classes
 * ({@link ContainerOperations}, {@link NetworkOperations}, {@link LogOperations},
 * {@link ImageOperations}). Stateless domain operations are delegated to those utilities.
 *
 * <p>It resides in the {@code nonapi} package and must never appear in the public API surface
 * of {@code Container} or {@code Network}, which are thin immutable facades.
 *
 * <h2>Concurrency and lifecycle</h2>
 *
 * <p>The shared instance is initialized lazily and is safe for concurrent use. The OkHttp
 * clients are immutable and thread-safe. Active log follow-streams are tracked in a concurrent
 * map so container destruction can release them.
 *
 * <p>Bootstrap failures surface as {@link ContainerException} at the first call to
 * {@link #instance()}, rather than as a class-loading error, so callers get a clear message
 * when Docker is unavailable.
 */
public final class DockerClient {

    /**
     * Overall deadline for confirming that a destroyed resource is actually gone.
     */
    private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Lazily-initialized, thread-safe singleton instance.
     */
    private static volatile DockerClient instance;

    /**
     * The low-level Docker HTTP client.
     */
    private final DockerHttpClient delegate;

    /**
     * Active log follow-stream handles keyed by container id, so destruction can release them.
     */
    private final ConcurrentHashMap<String, LogStreamHandle> logStreams = new ConcurrentHashMap<>();

    /**
     * Returns the shared, lazily-initialized {@link DockerClient} singleton.
     *
     * <p>Bootstrap (Docker endpoint resolution, validation, HTTP client, and ping) happens
     * once, at first use. If bootstrap fails, a {@link ContainerException} is thrown describing
     * the failure.
     *
     * @return the shared {@link DockerClient} instance
     * @throws ContainerException if the Docker client cannot be initialized
     */
    public static DockerClient instance() {
        DockerClient local = instance;
        if (local != null) {
            return local;
        }
        synchronized (DockerClient.class) {
            local = instance;
            if (local == null) {
                local = new DockerClient();
                instance = local;
            }
            return local;
        }
    }

    private DockerClient() {
        try {
            DockerEndpoint endpoint = DockerEndpoint.resolve();
            endpoint.validate();
            DockerHttpClient client = DockerHttpClient.create(endpoint);
            client.ping();
            this.delegate = client;
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to initialize Docker client", e);
        }
    }

    /**
     * Package-private constructor for tests that provide a pre-built delegate.
     *
     * @param delegate the DockerHttpClient to use
     */
    DockerClient(DockerHttpClient delegate) {
        this.delegate = delegate;
    }

    /**
     * Returns the low-level Docker HTTP client. Package-private for focused utility classes
     * ({@link ContainerOperations}, {@link NetworkOperations}, etc.) in this package.
     *
     * @return the Docker HTTP client
     */
    DockerHttpClient delegate() {
        return delegate;
    }

    /**
     * Stops and removes a container, blocking until Docker confirms it is gone.
     *
     * <p>Idempotent and terminal. Releases any tracked log follow-stream promptly, issues a
     * best-effort graceful stop, then force-removes the container while polling until it can no
     * longer be inspected. The remove command is re-issued on every poll iteration to handle
     * transient failures.
     *
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the container is not confirmed gone within
     *     {@link #DESTROY_TIMEOUT}, or if the calling thread is interrupted while waiting
     */
    public void destroyContainer(String id) {
        requireNonBlank(id, "id");
        closeLogStream(id);
        ContainerOperations.stopContainer(this, id);
        DestructionPoller.destroyAndAwait(
                "Docker container " + id, DESTROY_TIMEOUT, () -> DestructionPoller.removeContainerAndProbe(this, id));
    }

    /**
     * Removes a Docker network, blocking until Docker confirms it is gone.
     *
     * <p>Idempotent. Retries transient failures by re-issuing the remove on every poll iteration
     * while polling until the network can no longer be inspected.
     *
     * @param id the network identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the network is not confirmed gone within
     *     {@link #DESTROY_TIMEOUT}, or if the calling thread is interrupted while waiting
     */
    public void destroyNetwork(String id) {
        requireNonBlank(id, "id");
        DestructionPoller.destroyAndAwait(
                "Docker network " + id, DESTROY_TIMEOUT, () -> DestructionPoller.removeNetworkAndProbe(this, id));
    }

    /**
     * Copies a file into a container's filesystem via
     * {@code PUT /containers/{id}/archive}.
     *
     * @param id the container identifier; must not be blank
     * @param containerPath the destination directory inside the container
     * @param fileName the file name
     * @param content the file content
     * @param mode the file mode (e.g., 0777)
     * @throws ContainerException if the copy fails
     */
    public void putArchive(String id, String containerPath, String fileName, byte[] content, int mode) {
        requireNonBlank(id, "id");
        try {
            delegate.putArchive(id, containerPath, fileName, content, mode);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to copy file to container " + id, e);
        }
    }

    /**
     * Attaches a follow-stream to a container's combined stdout/stderr and registers a closeable
     * handle.
     *
     * <p>Frames are decoded as UTF-8 and reassembled into lines across frame boundaries. For each
     * non-blank line, {@code displayLineConsumer} receives the line with its trailing newline
     * stripped and {@code rawLineConsumer} receives the line including its trailing newline
     * (preserving whole-string regex matching). Partial lines awaiting a newline are buffered
     * between frames. When the callback closes, any remaining partial line in the buffer is
     * discarded.
     *
     * <p>If a log stream is already tracked for the given container id, the previous handle is
     * closed before the new one is stored.
     *
     * @param id the container identifier; must not be blank
     * @param displayLineConsumer receives stripped log lines; {@code null} treated as a no-op
     *     consumer
     * @param rawLineConsumer receives raw (newline-terminated) log lines; {@code null} treated as a
     *     no-op consumer
     * @return a closeable handle for the follow-stream
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if Docker fails to attach the log stream
     */
    public LogStreamHandle attachLogStream(
            String id, Consumer<String> displayLineConsumer, Consumer<String> rawLineConsumer) {
        requireNonBlank(id, "id");
        Closeable stream = LogOperations.attachStream(this, id, displayLineConsumer, rawLineConsumer);
        LogStreamHandle handle = new LogStreamHandle(stream);
        LogStreamHandle previous = logStreams.put(id, handle);
        if (previous != null) {
            previous.close();
        }
        return handle;
    }

    /**
     * Releases any tracked log follow-stream for the given container id.
     *
     * @param id the container identifier
     */
    void closeLogStream(String id) {
        LogStreamHandle handle = logStreams.remove(id);
        if (handle != null) {
            handle.close();
        }
    }

    // --- Validation helpers (package-private for focused utility classes) ---

    /**
     * Validates that a string value is not null or blank.
     *
     * @param value the value to check
     * @param name the parameter name for the error message
     * @throws IllegalArgumentException if the value is null or blank
     */
    static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Validates that a duration is not null, zero, or negative.
     *
     * @param value the duration to check
     * @param name the parameter name for the error message
     * @throws IllegalArgumentException if the duration is null, zero, or negative
     */
    static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
