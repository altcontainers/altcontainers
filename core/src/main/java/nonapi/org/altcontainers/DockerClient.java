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

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.Closeable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.altcontainers.api.ContainerException;

/**
 * The single, process-wide gateway for all Docker interaction.
 *
 * <p>{@code DockerClient} owns the docker-java client, the error boundary that normalizes Docker failures
 * to {@link ContainerException}, the UTF-8 log decoding, the destroy/poll orchestration, and host-file
 * preparation for bind mounts. It resides in the {@code nonapi} package and must never appear in the
 * public API surface of {@code Container} or {@code Network}, which are thin immutable facades that
 * delegate every operation here.
 *
 * <h2>Naming</h2>
 *
 * <p>This class's simple name ({@code DockerClient}) collides with the library type
 * {@code com.github.dockerjava.api.DockerClient}. To avoid ambiguity, the docker-java delegate is held as
 * a fully-qualified type and this is the only place in the module that references the library client
 * directly.
 *
 * <h2>Concurrency and lifecycle</h2>
 *
 * <p>The shared instance is initialized lazily and is safe for concurrent use. The docker-java client and
 * its HTTP transport are themselves thread-safe. Active log follow-streams are tracked in a concurrent map
 * so container destruction can release them. A JVM shutdown hook performs best-effort force-removal of all
 * tracked containers. Docker networks are not tracked and are not cleaned during shutdown.
 *
 * <p>Bootstrap failures surface as {@link ContainerException} at the first call to {@link #instance()},
 * rather than as a class-loading error, so callers get a clear message when Docker is unavailable.
 */
public final class DockerClient {

    /**
     * Network driver used when creating bridge networks.
     */
    private static final String DRIVER_BRIDGE = "bridge";

    /**
     * Overall deadline for confirming that a destroyed resource is actually gone.
     */
    private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Maximum time, in seconds, to wait for a graceful container stop before force-removing.
     */
    private static final int STOP_TIMEOUT_SECONDS = 2;

    /**
     * Charset used to decode container log frames.
     */
    private static final Charset LOG_CHARSET = StandardCharsets.UTF_8;

    /**
     * Lazily-initialized, thread-safe singleton instance.
     */
    private static volatile DockerClient instance;

    /**
     * The docker-java delegate, referenced by fully-qualified type to avoid a name clash.
     */
    private final com.github.dockerjava.api.DockerClient delegate;

    /**
     * Active log follow-stream handles keyed by container id, so destruction can release them.
     */
    private final ConcurrentHashMap<String, LogStreamHandle> logStreams = new ConcurrentHashMap<>();

    /**
     * Returns the shared, lazily-initialized {@link DockerClient} singleton.
     *
     * <p>Bootstrap (Docker client plus HTTP transport) happens once, at first use. If bootstrap fails, a
     * {@link ContainerException} is thrown describing the failure.
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
            var configuration =
                    DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var httpClient = new ZerodepDockerHttpClient.Builder()
                    .dockerHost(configuration.getDockerHost())
                    .build();
            this.delegate = DockerClientImpl.getInstance(configuration, httpClient);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to initialize Docker client", e);
        }
    }

    /**
     * Ensures the given Docker image is present on the Docker host, pulling it if it is missing.
     *
     * <p>Note: {@code awaitCompletion()} in the pull path has no local timeout; the test infrastructure is
     * responsible for enforcing overall deadlines.
     *
     * @param image the Docker image name; must not be blank
     * @throws IllegalArgumentException if {@code image} is blank
     * @throws ContainerException if the image cannot be inspected or pulled
     */
    public void pullImageIfMissing(String image) {
        requireNonBlank(image, "image");
        try {
            delegate.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            try {
                delegate.pullImageCmd(image).start().awaitCompletion();
            } catch (InterruptedException interrupt) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while pulling image: " + image, interrupt);
            } catch (RuntimeException pullError) {
                throw new ContainerException("Failed to pull image: " + image, pullError);
            }
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect image: " + image, e);
        }
    }

    /**
     * Creates, but does not start, a Docker container from the given specification.
     *
     * <p>Resource limits from the specification are applied via {@link HostConfig}. The container must be
     * started separately with {@link #startContainer(String)}.
     *
     * @param spec the container specification; must not be {@code null}
     * @return the newly created container's identifier
     * @throws IllegalArgumentException if {@code spec} is {@code null}
     * @throws ContainerException if Docker refuses or fails the create request
     */
    public String createContainer(ContainerCreateSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        try {
            List<ExposedPort> exposedPorts =
                    spec.exposedPorts().stream().map(ExposedPort::tcp).toList();

            List<PortBinding> portBindings = spec.exposedPorts().stream()
                    .map(port -> new PortBinding(Ports.Binding.empty(), ExposedPort.tcp(port)))
                    .toList();

            List<Bind> binds = spec.bindMounts().stream()
                    .map(mount -> new Bind(mount.hostPath(), new Volume(mount.containerPath()), AccessMode.rw))
                    .toList();

            HostConfig hostConfig = HostConfig.newHostConfig().withBinds(binds).withPortBindings(portBindings);

            if (spec.networkMode() != null) {
                hostConfig.withNetworkMode(spec.networkMode());
            }

            if (spec.memory() > 0) {
                hostConfig.withMemory(spec.memory());
            }
            if (spec.memorySwap() > 0) {
                hostConfig.withMemorySwap(spec.memorySwap());
            }
            if (spec.shmSize() > 0) {
                hostConfig.withShmSize(spec.shmSize());
            }
            if (spec.cpuShares() > 0) {
                hostConfig.withCpuShares(spec.cpuShares());
            }
            if (spec.cpuPeriod() > 0) {
                hostConfig.withCpuPeriod(spec.cpuPeriod());
            }
            if (spec.cpuQuota() > 0) {
                hostConfig.withCpuQuota(spec.cpuQuota());
            }
            if (!spec.ulimits().isEmpty()) {
                hostConfig.withUlimits(spec.ulimits().stream()
                        .map(u -> new Ulimit(u.name(), u.soft(), u.hard()))
                        .toArray(Ulimit[]::new));
            }

            var createCmd = delegate.createContainerCmd(spec.image())
                    .withHostConfig(hostConfig)
                    .withExposedPorts(exposedPorts)
                    .withCmd(spec.command());

            if (spec.networkMode() != null && !spec.networkAliases().isEmpty()) {
                createCmd = createCmd.withAliases(spec.networkAliases());
            }

            if (spec.workingDirectory() != null) {
                createCmd = createCmd.withWorkingDir(spec.workingDirectory());
            }

            return createCmd.withLabels(spec.labels()).exec().getId();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to create container for image: " + spec.image(), e);
        }
    }

    /**
     * Starts a previously created container.
     *
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if Docker fails to start the container
     */
    public void startContainer(String id) {
        requireNonBlank(id, "id");
        try {
            delegate.startContainerCmd(id).exec();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to start container " + id, e);
        }
    }

    /**
     * Returns whether a container with the given identifier currently exists.
     *
     * @param id the container identifier; must not be blank
     * @return {@code true} if the container exists; {@code false} if it is absent
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the existence check itself fails for a non-absence reason
     */
    public boolean containerExists(String id) {
        requireNonBlank(id, "id");
        try {
            delegate.inspectContainerCmd(id).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect container " + id, e);
        }
    }

    /**
     * Returns whether the container is currently in the {@code running} state.
     *
     * <p>Absent containers are reported as not running rather than throwing.
     *
     * @param id the container identifier; must not be blank
     * @return {@code true} if the container exists and is running; {@code false} if it is absent or not
     *     running
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the state check itself fails for a non-absence reason
     */
    public boolean isContainerRunning(String id) {
        requireNonBlank(id, "id");
        try {
            var state = delegate.inspectContainerCmd(id).exec().getState();
            return state != null && "running".equals(state.getStatus());
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect container " + id, e);
        }
    }

    /**
     * Resolves the host port mapped to the given container port.
     *
     * <p>Returns {@code -1} for absent containers, missing port bindings, malformed port specs, or invalid
     * container port values. Only non-absence Docker inspection failures are escalated.
     *
     * @param id the container identifier; must not be blank
     * @param containerPort the container-side port; values outside {@code 1..65535} return {@code -1}
     * @return the mapped host port, or {@code -1} if no mapping exists, the container is gone, the port
     *     is invalid, or the port specification is malformed
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the port lookup itself fails for a non-absence reason
     */
    public int hostPort(String id, int containerPort) {
        requireNonBlank(id, "id");
        try {
            var ports =
                    delegate.inspectContainerCmd(id).exec().getNetworkSettings().getPorts();
            var bindings = ports.getBindings().get(ExposedPort.tcp(containerPort));
            if (bindings == null || bindings.length == 0 || bindings[0] == null) {
                return -1;
            }
            String hostPortSpec = bindings[0].getHostPortSpec();
            if (hostPortSpec == null || hostPortSpec.isBlank()) {
                return -1;
            }
            return Integer.parseInt(hostPortSpec);
        } catch (NotFoundException | NumberFormatException e) {
            return -1;
        } catch (RuntimeException e) {
            throw new ContainerException(
                    "Failed to resolve host port for container " + id + ", port " + containerPort, e);
        }
    }

    /**
     * Best-effort graceful stop of a container.
     *
     * <p>Absent containers are tolerated. Other failures are swallowed because a subsequent force-remove
     * will finalize destruction.
     *
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public void stopContainer(String id) {
        requireNonBlank(id, "id");
        try {
            delegate.stopContainerCmd(id).withTimeout(STOP_TIMEOUT_SECONDS).exec();
        } catch (RuntimeException ignored) {
            // Best-effort graceful stop (including NotFoundException); force-remove finalizes destruction.
        }
    }

    /**
     * Force-removes a container and releases any tracked log follow-stream for it.
     *
     * <p>Idempotent: absent containers are tolerated, as are transient removal failures. The associated log
     * follow-stream (if any) is always closed.
     *
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public void forceRemoveContainer(String id) {
        requireNonBlank(id, "id");
        try {
            delegate.removeContainerCmd(id).withForce(Boolean.TRUE).exec();
        } catch (RuntimeException ignored) {
            // Transient (including NotFoundException); destruction is confirmed by the caller via awaitContainerGone.
        }
        closeLogStream(id);
    }

    /**
     * Stops and removes a container, blocking until Docker confirms it is gone.
     *
     * <p>Idempotent and terminal. Releases any tracked log follow-stream promptly, issues a best-effort
     * graceful stop, then force-removes the container while polling until it can no longer be inspected.
     * The remove command is re-issued on every poll iteration to handle transient failures.
     *
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the container is not confirmed gone within {@link #DESTROY_TIMEOUT},
     *     or if the calling thread is interrupted while waiting
     */
    public void destroyContainer(String id) {
        requireNonBlank(id, "id");
        closeLogStream(id);
        stopContainer(id);
        destroyAndAwait("Docker container " + id, DESTROY_TIMEOUT, () -> removeContainerAndProbe(id));
    }

    /**
     * Blocks until the container can no longer be inspected, or until the timeout elapses.
     *
     * <p>Does not itself issue a remove; callers should first remove the container.
     *
     * @param id the container identifier; must not be blank
     * @param timeout the maximum time to wait; must not be {@code null} or non-positive
     * @throws IllegalArgumentException if {@code id} is blank or {@code timeout} is not positive
     * @throws ContainerException if the container is still present after {@code timeout}, or if the
     *     calling thread is interrupted while waiting
     */
    public void awaitContainerGone(String id, Duration timeout) {
        requireNonBlank(id, "id");
        requirePositive(timeout, "timeout");
        awaitAbsence("Docker container " + id, timeout, () -> !containerExists(id));
    }

    /**
     * Creates a Docker bridge network with the given name and labels.
     *
     * @param name the network name; must not be blank
     * @param labels the Docker labels to apply; must not be {@code null}
     * @return the Docker-assigned network identifier
     * @throws IllegalArgumentException if {@code name} is blank or {@code labels} is {@code null}
     * @throws ContainerException if Docker fails to create the network
     */
    public String createNetwork(String name, Map<String, String> labels) {
        requireNonBlank(name, "name");
        if (labels == null) {
            throw new IllegalArgumentException("labels must not be null");
        }
        try {
            return delegate.createNetworkCmd()
                    .withName(name)
                    .withDriver(DRIVER_BRIDGE)
                    .withLabels(labels)
                    .exec()
                    .getId();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to create Docker network: " + name, e);
        }
    }

    /**
     * Returns whether a network with the given identifier currently exists.
     *
     * @param id the network identifier; must not be blank
     * @return {@code true} if the network exists; {@code false} if it is absent
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the existence check itself fails for a non-absence reason
     */
    public boolean networkExists(String id) {
        requireNonBlank(id, "id");
        try {
            delegate.inspectNetworkCmd().withNetworkId(id).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect Docker network " + id, e);
        }
    }

    /**
     * Lists container ids matching the given label filter.
     *
     * <p>This is package-private for use by {@code nonapi.org.altcontainers.reaper.ResourceCleaner}.
     *
     * @param labels the label filter map (AND semantics)
     * @return the matching container ids (may be empty)
     * @throws ContainerException if the list operation fails
     */
    public List<String> listContainerIdsByLabels(Map<String, String> labels) {
        try {
            return delegate.listContainersCmd().withLabelFilter(labels).withShowAll(true).exec().stream()
                    .map(com.github.dockerjava.api.model.Container::getId)
                    .toList();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to list containers by labels", e);
        }
    }

    /**
     * Lists network ids matching the given label filter.
     *
     * <p>Uses generic Docker filter API ({@code withFilter("label", ...)}) because
     * {@code ListNetworksCmd} lacks a dedicated {@code withLabelFilter(Map)} method.
     *
     * <p>This is package-private for use by {@code nonapi.org.altcontainers.reaper.ResourceCleaner}.
     *
     * @param labels the label filter map; each entry becomes a {@code label=key=value} filter
     * @return the matching network ids (may be empty)
     * @throws ContainerException if the list operation fails
     */
    public List<String> listNetworkIdsByLabels(Map<String, String> labels) {
        try {
            var cmd = delegate.listNetworksCmd();
            for (var entry : labels.entrySet()) {
                cmd = cmd.withFilter("label", List.of(entry.getKey() + "=" + entry.getValue()));
            }
            return cmd.exec().stream()
                    .map(com.github.dockerjava.api.model.Network::getId)
                    .toList();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to list networks by labels", e);
        }
    }

    /**
     * Returns the labels of a container.
     *
     * <p>This is package-private for use by {@code nonapi.org.altcontainers.reaper.ResourceCleaner}.
     *
     * @param id the container identifier; must not be blank
     * @return the container labels, or an empty map if absent or unavailable
     */
    public Map<String, String> inspectContainerLabels(String id) {
        requireNonBlank(id, "id");
        try {
            var labels = delegate.inspectContainerCmd(id).exec().getConfig().getLabels();
            return labels != null ? labels : Map.of();
        } catch (NotFoundException e) {
            return Map.of();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect container labels for " + id, e);
        }
    }

    /**
     * Returns the labels of a network.
     *
     * <p>This is package-private for use by {@code nonapi.org.altcontainers.reaper.ResourceCleaner}.
     *
     * @param id the network identifier; must not be blank
     * @return the network labels, or an empty map if absent or unavailable
     */
    public Map<String, String> inspectNetworkLabels(String id) {
        requireNonBlank(id, "id");
        try {
            var labels = delegate.inspectNetworkCmd().withNetworkId(id).exec().getLabels();
            return labels != null ? labels : Map.of();
        } catch (NotFoundException e) {
            return Map.of();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect network labels for " + id, e);
        }
    }

    /**
     * Lists all Docker networks, returning their names and ids.
     *
     * <p>This is package-private for use by {@code nonapi.org.altcontainers.reaper.ResourceCleaner}.
     *
     * @return all networks (may be empty)
     * @throws ContainerException if the list operation fails
     */
    public List<com.github.dockerjava.api.model.Network> listAllNetworks() {
        try {
            return delegate.listNetworksCmd().exec();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to list networks", e);
        }
    }

    /**
     * Removes a Docker network. Idempotent: absent networks are tolerated.
     *
     * @param id the network identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public void removeNetwork(String id) {
        requireNonBlank(id, "id");
        try {
            delegate.removeNetworkCmd(id).exec();
        } catch (RuntimeException ignored) {
            // Best-effort (including NotFoundException); destroyNetwork confirms via awaitNetworkGone.
        }
    }

    /**
     * Removes a Docker network, blocking until Docker confirms it is gone.
     *
     * <p>Idempotent. Retries transient {@code endpoint still attached} failures by re-issuing the remove
     * on every poll iteration while polling until the network can no longer be inspected.
     *
     * @param id the network identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the network is not confirmed gone within {@link #DESTROY_TIMEOUT},
     *     or if the calling thread is interrupted while waiting
     */
    public void destroyNetwork(String id) {
        requireNonBlank(id, "id");
        destroyAndAwait("Docker network " + id, DESTROY_TIMEOUT, () -> removeNetworkAndProbe(id));
    }

    /**
     * Blocks until the network can no longer be inspected, or until the timeout elapses.
     *
     * <p>Does not itself issue a remove; callers should first remove the network.
     *
     * @param id the network identifier; must not be blank
     * @param timeout the maximum time to wait; must not be {@code null} or non-positive
     * @throws IllegalArgumentException if {@code id} is blank or {@code timeout} is not positive
     * @throws ContainerException if the network is still present after {@code timeout}, or if the calling
     *     thread is interrupted while waiting
     */
    public void awaitNetworkGone(String id, Duration timeout) {
        requireNonBlank(id, "id");
        requirePositive(timeout, "timeout");
        awaitAbsence("Docker network " + id, timeout, () -> !networkExists(id));
    }

    /**
     * Attaches a follow-stream to a container's combined stdout/stderr and registers a closeable handle.
     *
     * <p>Frames are decoded as UTF-8 and reassembled into lines across frame boundaries. For each
     * non-blank line, {@code displayLineConsumer} receives the line with its trailing newline stripped and
     * {@code rawLineConsumer} receives the line including its trailing newline (preserving whole-string
     * regex matching). Partial lines awaiting a newline are buffered between frames. When the callback
     * closes, any remaining partial line in the buffer is discarded.
     *
     * <p>If a log stream is already tracked for the given container id, the previous handle is closed
     * before the new one is stored.
     *
     * <p>The returned handle is also tracked internally and closed when the container is destroyed, so
     * closing it explicitly is optional but harmless.
     *
     * @param id the container identifier; must not be blank
     * @param displayLineConsumer receives stripped log lines; {@code null} treated as a no-op consumer
     * @param rawLineConsumer receives raw (newline-terminated) log lines; {@code null} treated as a no-op
     *     consumer
     * @return a closeable handle for the follow-stream
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if Docker fails to attach the log stream
     */
    public LogStreamHandle attachLogStream(
            String id, Consumer<String> displayLineConsumer, Consumer<String> rawLineConsumer) {
        requireNonBlank(id, "id");
        Consumer<String> display = displayLineConsumer != null ? displayLineConsumer : line -> {};
        Consumer<String> raw = rawLineConsumer != null ? rawLineConsumer : line -> {};
        LineBuffer lineBuffer = new LineBuffer();
        ResultCallback<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (frame == null || frame.getPayload() == null) {
                    return;
                }
                lineBuffer.append(frame.getPayload());
                lineBuffer.drainLines(display, raw);
            }
        };
        Closeable returned;
        try {
            returned = delegate.logContainerCmd(id)
                    .withStdOut(Boolean.TRUE)
                    .withStdErr(Boolean.TRUE)
                    .withFollowStream(Boolean.TRUE)
                    .withTailAll()
                    .exec(callback);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to attach log stream for container " + id, e);
        }
        LogStreamHandle handle = new LogStreamHandle(returned);
        LogStreamHandle previous = logStreams.put(id, handle);
        if (previous != null) {
            previous.close();
        }
        return handle;
    }

    /**
     * Outcome of a presence probe during destruction.
     */
    private enum Presence {
        /**
         * The resource has been confirmed destroyed.
         */
        GONE,

        /**
         * The resource may still be present; keep retrying.
         */
        PRESENT
    }

    /**
     * Force-removes the container and probes its existence. The remove is re-issued on every call so that
     * transient failures (e.g., containers stuck in removal-pending) are retried until the timeout expires.
     *
     * @param id the container identifier
     * @return {@link Presence#GONE} if the container is absent; {@link Presence#PRESENT} otherwise
     */
    private Presence removeContainerAndProbe(String id) {
        try {
            delegate.removeContainerCmd(id).withForce(Boolean.TRUE).exec();
        } catch (NotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            // Transient; fall through to the existence check.
        }
        try {
            delegate.inspectContainerCmd(id).exec();
            return Presence.PRESENT;
        } catch (NotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            // Cannot confirm absence; keep retrying until the deadline.
            return Presence.PRESENT;
        }
    }

    /**
     * Removes the network and probes its existence. The remove is re-issued on every call so that
     * transient failures (e.g., endpoints still attached) are retried until the timeout expires.
     *
     * @param id the network identifier
     * @return {@link Presence#GONE} if the network is absent; {@link Presence#PRESENT} otherwise
     */
    private Presence removeNetworkAndProbe(String id) {
        try {
            delegate.removeNetworkCmd(id).exec();
        } catch (NotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            // Transient (e.g. endpoints still attached); fall through to the existence check.
        }
        try {
            delegate.inspectNetworkCmd().withNetworkId(id).exec();
            return Presence.PRESENT;
        } catch (NotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            // Cannot confirm absence; keep retrying until the deadline.
            return Presence.PRESENT;
        }
    }

    /**
     * Repeatedly executes a presence probe with exponential backoff and jitter until it reports the
     * resource gone, or until the timeout elapses.
     *
     * <p>Uses monotonic {@link System#nanoTime()} for deadline and elapsed-time calculations.
     * Delegates backoff sleep to {@link PollBackoff#sleepWithBackoff}.
     *
     * @param description a human-readable description of the resource, used in error messages
     * @param timeout the maximum time to wait; must not be {@code null} or non-positive
     * @param probe the presence probe to execute each iteration
     * @throws ContainerException if the resource is still present after {@code timeout}, or if the
     *     calling thread is interrupted while waiting
     */
    private void destroyAndAwait(String description, Duration timeout, Supplier<Presence> probe) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        while (true) {
            long iterationStartNanos = System.nanoTime();
            if (probe.get() == Presence.GONE) {
                return;
            }
            long elapsedNanos = System.nanoTime() - iterationStartNanos;
            if (!PollBackoff.sleepWithBackoff(deadlineNanos, sleepMs, elapsedNanos)) {
                if (deadlineNanos - System.nanoTime() <= 0) {
                    break;
                }
                // Interrupted.
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while destroying " + description);
            }
        }
        throw new ContainerException("Failed to confirm destruction of " + description + " within " + timeout);
    }

    /**
     * Blocks until the given absence condition is {@code true}, or until the timeout elapses.
     *
     * @param description a human-readable description of the resource, used in error messages
     * @param timeout the maximum time to wait; must not be {@code null} or non-positive
     * @param isAbsent the absence condition to evaluate each iteration
     * @throws ContainerException if the resource is still present after {@code timeout}, or if the
     *     calling thread is interrupted while waiting
     */
    private void awaitAbsence(String description, Duration timeout, Supplier<Boolean> isAbsent) {
        destroyAndAwait(description, timeout, () -> isAbsent.get() ? Presence.GONE : Presence.PRESENT);
    }

    /**
     * Closes and forgets any tracked log follow-stream for the given container.
     *
     * <p>Idempotent.
     *
     * @param id the container identifier
     */
    private void closeLogStream(String id) {
        LogStreamHandle handle = logStreams.remove(id);
        if (handle != null) {
            handle.close();
        }
    }

    /**
     * A growable byte buffer that accumulates log-frame payloads and emits complete newline-terminated
     * lines without allocating intermediate copies of the entire buffer on every frame.
     *
     * <p>Used exclusively by {@link #attachLogStream(String, Consumer, Consumer)}. Partial lines (bytes
     * after the last newline) are compacted to the front of the buffer between frames. When the callback
     * closes, any remaining partial line is discarded. This buffer is not thread-safe; it is accessed only
     * by the single docker-java callback thread.
     */
    private static final class LineBuffer {

        /**
         * Initial backing-array capacity.
         */
        private static final int INITIAL_CAPACITY = 8192;

        /**
         * Backing array; grows as needed.
         */
        private byte[] data;

        /**
         * Index of the next write position (one past the last valid byte).
         */
        private int writePos;

        LineBuffer() {
            this.data = new byte[INITIAL_CAPACITY];
            this.writePos = 0;
        }

        /**
         * Appends the given payload bytes to the buffer, growing the backing array if necessary.
         *
         * @param payload the bytes to append; must not be {@code null}
         */
        void append(byte[] payload) {
            ensureCapacity(payload.length);
            System.arraycopy(payload, 0, data, writePos, payload.length);
            writePos += payload.length;
        }

        /**
         * Scans the buffer for complete newline-terminated lines, dispatches them to the consumers, and
         * compacts any trailing partial line to the front of the buffer.
         *
         * @param display receives stripped (no trailing newline) non-blank lines
         * @param raw receives raw newline-terminated lines
         */
        void drainLines(Consumer<String> display, Consumer<String> raw) {
            int lineStart = 0;
            for (int i = 0; i < writePos; i++) {
                if (data[i] == '\n') {
                    int lineLen = i - lineStart + 1;
                    String rawLine = new String(data, lineStart, lineLen, LOG_CHARSET);
                    String stripped = rawLine.endsWith("\n") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                    if (stripped.endsWith("\r")) {
                        stripped = stripped.substring(0, stripped.length() - 1);
                    }
                    if (!stripped.isBlank()) {
                        display.accept(stripped);
                        raw.accept(rawLine);
                    }
                    lineStart = i + 1;
                }
            }
            if (lineStart > 0) {
                int remaining = writePos - lineStart;
                if (remaining > 0) {
                    System.arraycopy(data, lineStart, data, 0, remaining);
                }
                writePos = remaining;
            }
        }

        /**
         * Ensures the backing array has room for {@code additional} bytes beyond the current write
         * position, growing it (doubling) if necessary.
         *
         * @param additional the number of additional bytes to accommodate
         */
        private void ensureCapacity(int additional) {
            int required = writePos + additional;
            if (required > data.length) {
                int newCapacity = Math.max(data.length * 2, required);
                byte[] newData = new byte[newCapacity];
                System.arraycopy(data, 0, newData, 0, writePos);
                data = newData;
            }
        }
    }

    /**
     * Throws {@link IllegalArgumentException} if the given value is blank.
     *
     * @param value the value to check
     * @param name the argument name used in the error message
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Throws {@link IllegalArgumentException} if the given duration is not positive.
     *
     * @param value the duration to check
     * @param name the argument name used in the error message
     * @throws IllegalArgumentException if {@code value} is {@code null}, zero, or negative
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
