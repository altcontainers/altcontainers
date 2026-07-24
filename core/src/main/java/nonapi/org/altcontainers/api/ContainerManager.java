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

package nonapi.org.altcontainers.api;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Ulimit;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.altcontainers.api.BindMount;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.OutputFrame;
import org.altcontainers.api.WaitStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container lifecycle facade — uses docker-java directly.
 */
public final class ContainerManager {

    private static final Logger logger = LoggerFactory.getLogger(ContainerManager.class);
    private static final ContainerManager INSTANCE = new ContainerManager();

    private static final long READINESS_POLL_INITIAL_MS;
    private static final long READINESS_POLL_MAX_MS;
    private static final long STARTUP_RETRY_BACKOFF_MULTIPLIER_MS;
    private static final long STARTUP_RETRY_BACKOFF_MAX_MS;
    private static final int PUT_ARCHIVE_PIPE_BUFFER_BYTES;

    static {
        AltcontainersProperties properties = AltcontainersProperties.instance();
        READINESS_POLL_INITIAL_MS = properties.containerReadinessPollInitial().toMillis();
        READINESS_POLL_MAX_MS = properties.containerReadinessPollMax().toMillis();
        STARTUP_RETRY_BACKOFF_MULTIPLIER_MS =
                properties.containerStartupRetryBackoffMultiplier().toMillis();
        STARTUP_RETRY_BACKOFF_MAX_MS =
                properties.containerStartupRetryBackoffMax().toMillis();
        PUT_ARCHIVE_PIPE_BUFFER_BYTES = properties.containerPutArchivePipeBufferBytes();
    }

    private static final ExecutorService STOP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "altcontainers-container-stop");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean shuttingDown;

    private final Map<String, LogHandle> logHandles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inflightPulls = new ConcurrentHashMap<>();
    private volatile Function<String, ContainerMetadata> metadataInspector = this::inspectAfterStart;
    private final Set<String> localImageCache = ConcurrentHashMap.newKeySet();

    private ContainerManager() {
        registerShutdownHook();
    }

    /**
     * Registers a JVM shutdown hook that gracefully shuts down the stop executor.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            shuttingDown = true;
                            STOP_EXECUTOR.shutdown();
                            try {
                                STOP_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                STOP_EXECUTOR.shutdownNow();
                            }
                        },
                        "altcontainers-container-stop-shutdown"));
    }

    /**
     * Returns the singleton instance.
     *
     * @return the singleton instance
     */
    public static ContainerManager getInstance() {
        return INSTANCE;
    }

    /**
     * Exposes pullImage for testing. Package-private.
     *
     * @param image the image name
     */
    void triggerPullImage(String image) {
        pullImage(image);
    }

    /**
     * Installs a post-start metadata inspector for internal lifecycle tests.
     *
     * @param inspector the metadata inspector
     */
    void setMetadataInspectorForTesting(Function<String, ContainerMetadata> inspector) {
        metadataInspector = Objects.requireNonNull(inspector, "inspector must not be null");
    }

    /**
     * Restores the production post-start metadata inspector.
     */
    void resetMetadataInspectorForTesting() {
        metadataInspector = this::inspectAfterStart;
    }

    /**
     * Returns the Docker client.
     *
     * @return the Docker client
     */
    private DockerClient dockerClient() {
        return DockerClientFactory.client();
    }

    /**
     * Checks whether the given image exists in the local Docker daemon
     * image store. Returns {@code false} for a {@link NotFoundException}
     * (image not pulled); rethrows other daemon communication errors.
     *
     * @param image the image name
     * @return {@code true} if the image exists locally
     * @throws RuntimeException on daemon communication errors other than 404
     */
    private boolean isImageAvailableLocally(String image) {
        try {
            dockerClient().inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /**
     * Pulls an image with deduplication. Concurrent callers for the same image
     * wait for the in-flight pull.
     *
     * @param image the image name
     */
    private void pullImage(String image) {
        while (true) {
            CompletableFuture<Void> newFuture = new CompletableFuture<>();
            CompletableFuture<Void> existing = inflightPulls.putIfAbsent(image, newFuture);
            if (existing == null) {
                try {
                    dockerClient().pullImageCmd(image).start().awaitCompletion();
                    newFuture.complete(null);
                    localImageCache.add(image);
                    inflightPulls.remove(image, newFuture);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    newFuture.completeExceptionally(e);
                    inflightPulls.remove(image);
                    throw new ContainerException("Image pull interrupted: " + image, e);
                } catch (RuntimeException e) {
                    newFuture.completeExceptionally(e);
                    inflightPulls.remove(image);
                    throw e instanceof ContainerException ce
                            ? ce
                            : new ContainerException("Image pull failed: " + image, e);
                }
            }
            try {
                existing.get();
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ContainerException ce) {
                    throw ce;
                }
                throw new ContainerException("Image pull failed: " + image, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for image pull", e);
            }
        }
    }

    /**
     * Creates a container, recovering once when a cached image was removed from
     * the Docker daemon.
     *
     * @param spec the container specification
     * @param labels the resource labels
     * @return the Docker container id
     */
    private String createContainerWithImageRecovery(ContainerSpec spec, Map<String, String> labels) {
        try {
            return createContainer(spec, labels);
        } catch (RuntimeException e) {
            String image = spec.image();
            if (!isMissingImageFailure(e, image) || !localImageCache.contains(image)) {
                throw e;
            }
            localImageCache.remove(image);
            if (isImageAvailableLocally(image)) {
                localImageCache.add(image);
            } else {
                pullImage(image);
            }
            return createContainer(spec, labels);
        }
    }

    /**
     * Returns whether a Docker failure identifies the requested image as
     * unavailable.
     *
     * @param failure the Docker failure
     * @param image the requested image
     * @return {@code true} if the failure is an image-not-found failure
     */
    private static boolean isMissingImageFailure(Throwable failure, String image) {
        String imageLower = image.toLowerCase(java.util.Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof NotFoundException notFound && notFound.getHttpStatus() == 404) {
                String message = current.getMessage();
                if (message == null) {
                    return false;
                }
                String messageLower = message.toLowerCase(java.util.Locale.ROOT);
                return messageLower.contains("no such image") || messageLower.contains(imageLower);
            }
        }
        return false;
    }

    /**
     * Creates, starts, and waits for a container to become ready.
     *
     * @param spec the container spec; must not be {@code null}
     * @return a container handle
     * @throws ContainerException if creation, startup, or readiness fails
     */
    public Container createContainer(ContainerSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        ReaperController ctrl = ReaperController.instance();
        ResourceSession session = ctrl.ensureReady();

        String image = spec.image();
        if (!localImageCache.contains(image) && !isImageAvailableLocally(image)) {
            pullImage(image);
        } else {
            localImageCache.add(image);
        }

        ContainerException lastFailure = null;
        for (int attempt = 1; attempt <= spec.startupAttempts(); attempt++) {
            Container container = null;
            String containerId = null;
            try {
                Map<String, String> labels = session.labelsForNewResource();
                containerId = createContainerWithImageRecovery(spec, labels);

                try {
                    dockerClient().startContainerCmd(containerId).exec();
                } catch (RuntimeException startEx) {
                    try {
                        container = new ConcreteContainer(
                                containerId, spec.image(), spec, metadataInspector.apply(containerId));
                    } catch (RuntimeException metadataFailure) {
                        startEx.addSuppressed(metadataFailure);
                    }
                    throw startEx instanceof ContainerException ce
                            ? ce
                            : new ContainerException("Failed to start container", startEx);
                }

                container =
                        new ConcreteContainer(containerId, spec.image(), spec, metadataInspector.apply(containerId));

                List<WaitStrategy> waitConditions = newAttemptConditions(spec);
                LogHandle logHandle = attachLogs(containerId, spec, waitConditions);

                spec.startupCheckStrategy().waitUntilStartupSuccessful(container, spec.startupTimeout());
                Consumer<Container> prepare = spec.prepare();
                if (prepare != null) {
                    prepare.accept(container);
                }
                waitUntilReady(container, waitConditions, spec.startupTimeout());
                if (spec.outputListener() == null) {
                    closeLogHandle(containerId);
                }

                return container;
            } catch (ContainerException e) {
                lastFailure = e;
                destroyAttempt(container, containerId);
            } catch (RuntimeException e) {
                String message =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                lastFailure = new ContainerException("Container startup failed: " + message, e);
                destroyAttempt(container, containerId);
            }
            if (attempt < spec.startupAttempts()) {
                sleepBeforeRetry(attempt);
            }
        }
        throw lastFailure != null ? lastFailure : new ContainerException("Container startup failed");
    }

    /**
     * Creates a Docker container with the given spec and labels.
     *
     * @param spec the container spec
     * @param labels the Docker labels to apply
     * @return the Docker container id
     */
    @SuppressWarnings("unchecked")
    private String createContainer(ContainerSpec spec, Map<String, String> labels) {
        try {
            var createCmd = dockerClient().createContainerCmd(spec.image()).withCmd(spec.command());

            if (!spec.environment().isEmpty()) {
                List<String> envList = new ArrayList<>();
                spec.environment().forEach((k, v) -> envList.add(k + "=" + v));
                createCmd.withEnv(envList);
            }
            if (!labels.isEmpty()) {
                createCmd.withLabels(labels);
            }
            if (!spec.networkAliases().isEmpty()) {
                createCmd.withAliases(spec.networkAliases());
            }

            HostConfig hostConfig = new HostConfig();
            List<ExposedPort> exposedPorts = new ArrayList<>();
            List<PortBinding> bindings = new ArrayList<>();
            Set<Integer> boundContainerPorts = new HashSet<>();
            if (!spec.portBindings().isEmpty()) {
                for (Map.Entry<Integer, Integer> e : spec.portBindings().entrySet()) {
                    int containerPort = e.getKey();
                    int hostPort = e.getValue();
                    ExposedPort exposedPort = ExposedPort.tcp(containerPort);
                    exposedPorts.add(exposedPort);
                    bindings.add(new PortBinding(Ports.Binding.bindPort(hostPort), exposedPort));
                    boundContainerPorts.add(containerPort);
                }
            }
            for (int containerPort : spec.exposedPorts()) {
                ExposedPort exposedPort = ExposedPort.tcp(containerPort);
                if (!exposedPorts.contains(exposedPort)) {
                    exposedPorts.add(exposedPort);
                }
                if (!boundContainerPorts.contains(containerPort)) {
                    bindings.add(new PortBinding(Ports.Binding.empty(), exposedPort));
                }
            }
            if (!exposedPorts.isEmpty()) {
                createCmd.withExposedPorts(exposedPorts);
            }
            if (!bindings.isEmpty()) {
                hostConfig.withPortBindings(bindings);
            }

            if (spec.networkMode() != null && !spec.networkMode().isEmpty()) {
                hostConfig.withNetworkMode(spec.networkMode());
            }
            if (spec.workingDirectory() != null) {
                createCmd.withWorkingDir(spec.workingDirectory());
            }

            if (!spec.bindMounts().isEmpty()) {
                List<Bind> binds = new ArrayList<>();
                for (BindMount bm : spec.bindMounts()) {
                    binds.add(Bind.parse(bm.hostPath() + ":" + bm.containerPath()));
                }
                hostConfig.withBinds(binds);
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
                List<Ulimit> ulimitList = new ArrayList<>();
                for (org.altcontainers.api.Ulimit u : spec.ulimits()) {
                    ulimitList.add(new Ulimit(u.name(), u.soft(), u.hard()));
                }
                hostConfig.withUlimits(ulimitList);
            }

            createCmd.withHostConfig(hostConfig);
            CreateContainerResponse response = createCmd.exec();
            return response.getId();
        } catch (RuntimeException e) {
            throw e instanceof ContainerException ce ? ce : new ContainerException("Failed to create container", e);
        }
    }

    /**
     * Inspects the container after start to collect port bindings and
     * running status.
     *
     * @param containerId the Docker container id
     * @return post-start metadata including host, running flag, and port bindings
     */
    private ContainerMetadata inspectAfterStart(String containerId) {
        InspectContainerResponse response;
        try {
            response = dockerClient().inspectContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            logger.warn("Failed to inspect container {} for start metadata: {}", containerId, e.getMessage());
            return new ContainerMetadata(host(), false, Map.of());
        }
        String host = host();
        boolean running = Boolean.TRUE.equals(response.getState().getRunning());
        var bindings = response.getNetworkSettings().getPorts().getBindings();
        Map<Integer, Integer> portBindings = new HashMap<>();
        if (bindings != null) {
            for (var entry : bindings.entrySet()) {
                if (entry.getValue() != null && entry.getValue().length > 0) {
                    try {
                        portBindings.put(
                                entry.getKey().getPort(), Integer.parseInt(entry.getValue()[0].getHostPortSpec()));
                    } catch (NumberFormatException e) {
                        throw new ContainerException(
                                "Docker returned non-numeric host port spec '"
                                        + entry.getValue()[0].getHostPortSpec()
                                        + "' for container port "
                                        + entry.getKey().getPort(),
                                e);
                    }
                }
            }
        }
        return new ContainerMetadata(host, running, Map.copyOf(portBindings));
    }

    /**
     * Destroys a container. Null-safe.
     *
     * @param container the container to close; may be {@code null}
     */
    public void closeContainer(Container container) {
        if (container == null) {
            return;
        }
        closeLogHandle(container.id());
        stopAndRemoveContainer(container.id());
    }

    /**
     * Returns whether the container is running.
     *
     * @param id the Docker container id
     * @return {@code true} if the container is running
     */
    public boolean isContainerRunning(String id) {
        try {
            InspectContainerResponse response =
                    dockerClient().inspectContainerCmd(id).exec();
            return Boolean.TRUE.equals(response.getState().getRunning());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Returns the Docker host for published container ports.
     *
     * @return the Docker host
     */
    public String host() {
        return DockerClientFactory.containerHost();
    }

    /**
     * Copies a file into the container.
     *
     * @param id the Docker container id
     * @param containerPath the destination path in the container
     * @param fileName the file name
     * @param content the file content bytes
     * @param mode the file mode
     */
    public void putArchive(String id, String containerPath, String fileName, byte[] content, int mode) {
        if (fileName.getBytes(StandardCharsets.UTF_8).length > 100) {
            throw new ContainerException("Tar entry name is too long: " + fileName);
        }
        putArchive(id, containerPath, fileName, new ByteArrayInputStream(content), content.length, mode);
    }

    /**
     * Copies a tar archive into the container via a piped stream.
     *
     * @param containerId the Docker container id
     * @param containerPath the destination path in the container
     * @param fileName the file name
     * @param content the file content stream
     * @param contentLength the content length in bytes
     * @param mode the file mode
     */
    void putArchive(
            String containerId,
            String containerPath,
            String fileName,
            InputStream content,
            long contentLength,
            int mode) {
        try {
            AtomicReference<IOException> writerFailure = new AtomicReference<>();
            PipedInputStream pipedIn = new PipedInputStream(PUT_ARCHIVE_PIPE_BUFFER_BYTES);
            PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);
            Thread writer = new Thread(
                    () -> {
                        try (pipedOut) {
                            writeTarStream(pipedOut, fileName, content, contentLength, mode);
                        } catch (IOException e) {
                            writerFailure.compareAndSet(null, e);
                        }
                    },
                    "altcontainers-core-tar-writer");
            writer.setDaemon(true);
            writer.start();
            RuntimeException execException = null;
            try {
                dockerClient()
                        .copyArchiveToContainerCmd(containerId)
                        .withTarInputStream(pipedIn)
                        .withRemotePath(containerPath)
                        .exec();
            } catch (RuntimeException e) {
                execException = e;
            } finally {
                try {
                    pipedIn.close();
                } catch (IOException ignored) {
                }
                try {
                    writer.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ContainerException("Interrupted waiting for tar writer thread", e);
                }
            }
            IOException wf = writerFailure.get();
            if (execException != null) {
                if (wf != null) {
                    execException.addSuppressed(wf);
                }
                throw execException instanceof ContainerException ce
                        ? ce
                        : new ContainerException("Failed to copy archive to container", execException);
            }
            if (writer.isAlive()) {
                throw new ContainerException("Tar writer thread did not finish in time");
            }
            if (wf != null) {
                throw new ContainerException("Failed to copy archive to container: " + wf.getMessage(), wf);
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to copy archive to container", e);
        }
    }

    /**
     * Writes a POSIX ustar tar entry to the output stream.
     *
     * @param out the output stream
     * @param fileName the tar entry name
     * @param content the entry content
     * @param contentLength the content length in bytes
     * @param mode the file mode
     * @throws IOException if an I/O error occurs
     */
    private static void writeTarStream(
            OutputStream out, String fileName, InputStream content, long contentLength, int mode) throws IOException {
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            throw new IOException("Tar entry name is too long: " + fileName);
        }
        byte[] header = new byte[512];
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, contentLength);
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[262] = 0;
        header[263] = '0';
        header[264] = '0';
        writeChecksum(header);
        out.write(header);

        byte[] buf = new byte[8192];
        long remaining = contentLength;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = content.read(buf, 0, toRead);
            if (read < 0) {
                throw new IOException("Unexpected end of content stream");
            }
            out.write(buf, 0, read);
            remaining -= read;
        }

        int padding = (int) ((512 - (contentLength % 512)) % 512);
        if (padding > 0) {
            out.write(new byte[padding]);
        }
        out.write(new byte[1024]);
        out.flush();
    }

    /**
     * Writes an octal-encoded numeric value into the tar header.
     *
     * @param header the 512-byte tar header
     * @param offset the byte offset to write at
     * @param length the field length in bytes
     * @param value the numeric value to encode
     * @throws IOException if the value is too large for the field
     */
    private static void writeOctal(byte[] header, int offset, int length, long value) throws IOException {
        String octal = Long.toOctalString(value);
        int digits = length - 1;
        if (octal.length() > digits) {
            throw new IOException("Tar numeric value too large: " + value);
        }
        int padding = digits - octal.length();
        for (int i = 0; i < padding; i++) {
            header[offset + i] = '0';
        }
        byte[] octalBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(octalBytes, 0, header, offset + padding, octalBytes.length);
        header[offset + length - 1] = 0;
    }

    /**
     * Computes and writes the tar header checksum.
     *
     * @param header the 512-byte tar header
     */
    private static void writeChecksum(byte[] header) {
        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        String octal = String.format("%06o", checksum);
        byte[] checksumBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, header, 148, checksumBytes.length);
        header[154] = 0;
        header[155] = ' ';
    }

    /**
     * Attaches log streaming for the container.
     *
     * @param containerId the Docker container id
     * @param spec the container spec
     * @param waitConditions the wait strategies
     * @return the log handle, or {@code null} if no listener or wait log consumer
     */
    private LogHandle attachLogs(String containerId, ContainerSpec spec, List<WaitStrategy> waitConditions) {
        Consumer<OutputFrame> listener = spec.outputListener();
        Consumer<String> waitLogConsumer = waitLogConsumer(waitConditions);
        if (listener == null && waitLogConsumer == null) {
            return null;
        }
        LogHandle handle = startLogStream(containerId, listener, waitLogConsumer);
        logHandles.put(containerId, handle);
        return handle;
    }

    /**
     * Wraps a downstream consumer with line-splitting semantics.
     *
     * <p>Frames are buffered and complete lines (terminated by {@code \n})
     * are dispatched to the downstream consumer. Partial lines are retained
     * across frames. Call {@link LineSplittingConsumer#flush()} on stream
     * completion to emit any remaining partial line.
     *
     * <p>Line splitting applies only to the raw log consumer path used by
     * {@link LogWaitStrategy}. The {@link OutputFrame} listener continues to
     * receive raw Docker frames with no line-buffering, matching
     * testcontainers-java behavior.
     *
     * @param downstream the consumer that receives complete lines; must not
     *     be {@code null}
     * @return a line-splitting consumer wrapper
     */
    static LineSplittingConsumer lineSplitting(Consumer<String> downstream) {
        return new LineSplittingConsumer(downstream);
    }

    /**
     * A consumer that buffers incoming text and dispatches complete
     * newline-terminated lines to a downstream consumer.
     *
     * <p>Multi-line frames are split into individual lines. Split lines
     * across frames are reassembled. On {@link #flush()}, any non-empty
     * buffer remainder is dispatched as a final line without a trailing
     * newline.
     *
     * <p>Thread confinement: instances are only called from the single-threaded
     * {@code onNext}/{@code onComplete} callbacks in a log-stream subscription.
     */
    static final class LineSplittingConsumer implements Consumer<String> {
        private final Consumer<String> downstream;
        private final StringBuilder buffer = new StringBuilder();

        LineSplittingConsumer(Consumer<String> downstream) {
            this.downstream = Objects.requireNonNull(downstream, "downstream must not be null");
        }

        @Override
        public void accept(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            int prevLength = buffer.length();
            buffer.append(text);
            int start = 0;
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (int i = prevLength; i < buffer.length(); i++) {
                if (buffer.charAt(i) == '\n') {
                    lines.add(buffer.substring(start, i + 1));
                    start = i + 1;
                }
            }
            buffer.delete(0, start);
            for (String line : lines) {
                downstream.accept(line);
            }
        }

        /**
         * Flushes any remaining buffered text as a final line.
         * Called when the log stream completes.
         */
        void flush() {
            if (!buffer.isEmpty()) {
                downstream.accept(buffer.toString());
                buffer.setLength(0);
            }
        }
    }

    /**
     * Starts a log stream for the container, dispatching to the output frame
     * listener and raw log consumers.
     *
     * <p>Output frame listener receives raw Docker frames matching
     * testcontainers-java {@code OutputFrame} semantics — the framework
     * does not strip, filter, decode, or line-buffer frames before
     * invoking the output listener.
     *
     * <p>The raw log consumer path (used by {@link LogWaitStrategy})
     * is wrapped with line-splitting to deliver complete lines.
     *
     * @param containerId the Docker container id
     * @param listener the output frame listener, or {@code null}
     * @param rawConsumer the raw log consumer, or {@code null}
     * @return a handle for the attached log stream
     */
    private LogHandle startLogStream(String containerId, Consumer<OutputFrame> listener, Consumer<String> rawConsumer) {
        LogHandle handle = new LogHandle();

        LineSplittingConsumer lineSplitter = rawConsumer != null ? lineSplitting(rawConsumer) : null;
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                OutputFrame.Type outputType;
                StreamType streamType = frame.getStreamType();
                if (streamType == StreamType.STDOUT) {
                    outputType = OutputFrame.Type.STDOUT;
                } else if (streamType == StreamType.STDERR) {
                    outputType = OutputFrame.Type.STDERR;
                } else if (streamType == StreamType.RAW) {
                    outputType = OutputFrame.Type.RAW;
                } else {
                    outputType = OutputFrame.Type.UNKNOWN;
                }
                OutputFrame outputFrame = new OutputFrame(outputType, payload);

                String text = new String(payload, StandardCharsets.UTF_8);
                if (lineSplitter != null) {
                    try {
                        lineSplitter.accept(text);
                    } catch (RuntimeException e) {
                        logger.warn("Log raw consumer failed: {}", e.getMessage());
                    }
                }
                if (listener != null && !handle.isClosed()) {
                    try {
                        listener.accept(outputFrame);
                    } catch (RuntimeException e) {
                        logger.warn("Container output listener failed: {}", e.getMessage());
                    }
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (lineSplitter != null) {
                    try {
                        lineSplitter.flush();
                    } catch (RuntimeException e) {
                        logger.warn("Log raw consumer failed during error flush", e);
                    }
                }
                if (shouldSuppressLogError(handle, throwable)) {
                    logger.debug(
                            "Log stream closed during shutdown for container {}: {}",
                            containerId,
                            throwable.getMessage() != null
                                    ? throwable.getMessage()
                                    : throwable.getClass().getName());
                } else {
                    logger.warn(
                            "Log stream error for container {}: {}",
                            containerId,
                            throwable.getMessage() != null
                                    ? throwable.getMessage()
                                    : throwable.getClass().getName());
                }
            }

            @Override
            public void onComplete() {
                if (lineSplitter != null) {
                    try {
                        lineSplitter.flush();
                    } catch (RuntimeException e) {
                        logger.warn("Log raw consumer failed during flush", e);
                    }
                }
            }
        };
        try {
            dockerClient()
                    .logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTailAll()
                    .exec(callback);
            handle.callback = callback;
            return handle;
        } catch (RuntimeException e) {
            closeQuietly(callback);
            throw e instanceof ContainerException ce ? ce : new ContainerException("Failed to attach log stream", e);
        }
    }

    /**
     * Closes a result callback, ignoring any I/O errors.
     *
     * @param callback the callback to close
     */
    private static void closeQuietly(ResultCallback<?> callback) {
        try {
            callback.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Builds a composite log consumer that fans out to all log-observing
     * managed wait strategies.
     *
     * @param waitConditions the wait strategies
     * @return a composite log consumer, or {@code null} if none observe logs
     */
    private static Consumer<String> waitLogConsumer(List<WaitStrategy> waitConditions) {
        List<Consumer<String>> consumers = waitConditions.stream()
                .filter(ws -> ws instanceof ManagedWaitStrategy)
                .map(ws -> ((ManagedWaitStrategy) ws).logLineConsumer())
                .filter(Objects::nonNull)
                .toList();
        if (consumers.isEmpty()) {
            return null;
        }
        return line -> consumers.forEach(consumer -> consumer.accept(line));
    }

    /**
     * Polls all wait strategies until every one is satisfied or the timeout
     * expires.
     *
     * @param container the container handle
     * @param waitConditions the wait strategies to poll
     * @param startupTimeout the readiness timeout
     * @throws ContainerException if the timeout expires before all strategies are satisfied
     */
    private static void waitUntilReady(
            Container container, List<WaitStrategy> waitConditions, Duration startupTimeout) {
        if (waitConditions.isEmpty()) {
            return;
        }
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new ContainerException("startupTimeout must be positive");
        }

        long deadlineNanos = System.nanoTime() + startupTimeout.toNanos();
        long pollMs = READINESS_POLL_INITIAL_MS;
        while (System.nanoTime() <= deadlineNanos) {
            if (waitConditions.stream().allMatch(condition -> condition.check(container))) {
                return;
            }
            long remainingMillis =
                    Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis();
            if (remainingMillis <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(pollMs, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for container readiness", e);
            }
            pollMs = Math.min(pollMs * 2, READINESS_POLL_MAX_MS);
        }

        String diagnostics = String.join(
                "; ",
                waitConditions.stream()
                        .map(condition -> condition instanceof ManagedWaitStrategy m
                                ? m.timeoutDiagnostic(container, startupTimeout)
                                : condition.getClass().getSimpleName())
                        .toList());
        throw new ContainerException("Container did not become ready: " + diagnostics);
    }

    /**
     * Destroys a container from a failed startup attempt. Closes the log handle,
     * stops, and force-removes the container. Does not fire {@code onClose}
     * callbacks.
     *
     * @param container the container to destroy; may be {@code null}
     */
    private void destroyAttempt(Container container) {
        if (container == null) {
            return;
        }
        closeLogHandle(container.id());
        stopAndRemoveContainer(container.id());
    }

    /**
     * Destroys a startup attempt using either its handle or its known Docker id.
     *
     * @param container the container handle, or {@code null}
     * @param containerId the Docker container id, or {@code null}
     */
    private void destroyAttempt(Container container, String containerId) {
        if (container != null) {
            destroyAttempt(container);
        } else if (containerId != null) {
            closeLogHandle(containerId);
            stopAndRemoveContainer(containerId);
        }
    }

    private static final int CONTAINER_REMOVE_RETRY_COUNT = 3;

    private static final long CONTAINER_REMOVE_RETRY_BASE_DELAY_MS = 200L;

    /**
     * Stops and removes a container via Docker, delegating cleanup to the
     * reaper process only when the stop times out or the remove fails after
     * retries.
     *
     * @param containerId the Docker container ID
     */
    private void stopAndRemoveContainer(String containerId) {
        if (shuttingDown) {
            logger.debug("Shutdown in progress, delegating container {} stop to reaper", containerId);
            delegateTerminateContainerToReaper(containerId);
            return;
        }
        long stopTimeoutSeconds =
                ReaperController.instance().configuration().reaperStopTimeout().toSeconds();
        int dockerStopTimeoutSeconds = (int) Math.min(stopTimeoutSeconds, Integer.MAX_VALUE);
        // Future timeout includes a buffer so Docker's own stop timeout doesn't race
        // the Java timeout, avoiding unnecessary delegation to the reaper.
        long futureTimeoutSeconds = Math.addExact(stopTimeoutSeconds, 10L);
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> dockerClient()
                            .stopContainerCmd(containerId)
                            .withTimeout(dockerStopTimeoutSeconds)
                            .exec(),
                    STOP_EXECUTOR);
            future.get(futureTimeoutSeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            logger.debug("Executor shut down, delegating container {} stop to reaper", containerId);
            delegateTerminateContainerToReaper(containerId);
            return;
        } catch (TimeoutException e) {
            logger.debug(
                    "Stop timed out for container {} after {}s, delegating to reaper",
                    containerId,
                    futureTimeoutSeconds);
            delegateTerminateContainerToReaper(containerId);
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException) {
                logger.debug("Stop: container {} not found (already removed)", containerId);
                return;
            }
            if (cause instanceof NotModifiedException) {
                logger.debug("Stop: container {} already stopped (Status 304)", containerId);
                // Container is already stopped — fall through to remove.
            } else {
                logger.debug(
                        "Stop failed for container {}, delegating to reaper: {}",
                        containerId,
                        cause != null ? cause.getMessage() : "unknown");
                delegateTerminateContainerToReaper(containerId);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted during stop for container {}, delegating to reaper", containerId);
            delegateTerminateContainerToReaper(containerId);
            return;
        }
        // Stop succeeded — remove synchronously with retry for transient failures.
        removeContainerWithRetry(containerId);
    }

    /**
     * Removes a container with retry for transient Docker daemon failures,
     * delegating to the reaper only as a last resort.
     *
     * @param containerId the Docker container ID
     */
    private void removeContainerWithRetry(String containerId) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < CONTAINER_REMOVE_RETRY_COUNT; attempt++) {
            try {
                dockerClient().removeContainerCmd(containerId).withForce(true).exec();
                return;
            } catch (NotFoundException e) {
                logger.debug("Remove: container {} not found (already removed)", containerId);
                return;
            } catch (NotModifiedException e) {
                logger.debug("Remove: container {} already removed (Status 304)", containerId);
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < CONTAINER_REMOVE_RETRY_COUNT - 1) {
                    logger.debug(
                            "Remove attempt {}/{} failed for container {}, retrying",
                            attempt + 1,
                            CONTAINER_REMOVE_RETRY_COUNT,
                            containerId);
                    try {
                        Thread.sleep(CONTAINER_REMOVE_RETRY_BASE_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        logger.debug(
                "Remove failed after {} retries for container {}, delegating to reaper: {}",
                CONTAINER_REMOVE_RETRY_COUNT,
                containerId,
                lastFailure != null ? lastFailure.getMessage() : "unknown");
        delegateTerminateContainerToReaper(containerId);
    }

    /**
     * Sends a TERMINATE_CONTAINER command to the reaper process for
     * asynchronous container cleanup.
     *
     * @param containerId the Docker container ID
     */
    private void delegateTerminateContainerToReaper(String containerId) {
        ReaperConnection conn = ReaperController.instance().reaperConnection();
        if (conn != null) {
            try {
                conn.sendTerminateContainer(containerId);
            } catch (IOException e) {
                logger.warn(
                        "Failed to send TERMINATE_CONTAINER to reaper for container {}: {}",
                        containerId,
                        e.getMessage());
            }
        } else {
            logger.warn("Reaper connection unavailable; container {} may not be cleaned up", containerId);
        }
    }

    /**
     * Closes and removes the log handle for the given container.
     *
     * @param containerId the Docker container id
     */
    private void closeLogHandle(String containerId) {
        LogHandle handle = logHandles.remove(containerId);
        if (handle != null) {
            handle.close();
        }
    }

    /**
     * Sleeps before the next startup retry, using linear backoff.
     *
     * @param failedAttempt the 1-based index of the failed attempt
     */
    private static void sleepBeforeRetry(int failedAttempt) {
        try {
            Thread.sleep(Math.min(STARTUP_RETRY_BACKOFF_MULTIPLIER_MS * failedAttempt, STARTUP_RETRY_BACKOFF_MAX_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerException("Interrupted before container startup retry", e);
        }
    }

    /**
     * Returns fresh copies of all managed wait strategies for a new startup attempt.
     * Lambda strategies are reused as-is.
     *
     * @param spec the container spec
     * @return fresh wait strategy instances for managed strategies, reused for lambdas
     */
    private static List<WaitStrategy> newAttemptConditions(ContainerSpec spec) {
        return spec.waitConditions().stream()
                .map(ws -> ws instanceof ManagedWaitStrategy m ? m.newAttemptCondition() : ws)
                .toList();
    }

    /**
     * Returns {@code true} if the throwable chain contains a
     * {@link java.nio.channels.ClosedByInterruptException}.
     *
     * @param t the throwable to inspect
     * @return {@code true} if the chain contains a closed-by-interrupt exception
     */
    static boolean isClosedByInterrupt(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof java.nio.channels.ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the log error should be suppressed because the
     * handle is already closed or the error was caused by an interrupt.
     *
     * @param handle the log handle, or {@code null}
     * @param throwable the error thrown by the log stream
     * @return {@code true} if the error should be suppressed
     */
    static boolean shouldSuppressLogError(LogHandle handle, Throwable throwable) {
        if (handle == null) {
            return false;
        }
        if (handle.isClosed()) {
            return true;
        }
        if (isClosedByInterrupt(throwable)) {
            return true;
        }
        return false;
    }

    /**
     * Lightweight handle for an attached log stream.
     */
    static final class LogHandle {
        private volatile ResultCallback<Frame> callback;
        private volatile boolean closed;

        /**
         * Returns whether this handle has been closed.
         *
         * @return {@code true} if closed
         */
        boolean isClosed() {
            return closed;
        }

        /**
         * Closes this handle and the underlying log stream callback.
         */
        void close() {
            closed = true;
            if (callback != null) {
                closeQuietly(callback);
            }
        }
    }
}
