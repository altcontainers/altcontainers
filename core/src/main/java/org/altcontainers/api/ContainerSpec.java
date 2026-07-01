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

package org.altcontainers.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable desired configuration for a Docker container.
 *
 * <p>{@code ContainerSpec} captures every configuration value needed to create, start, and wait for a
 * container. It is built via {@link #builder(String)} and consumed by
 * {@link Container#create(ContainerSpec)}. Instances are immutable and safe to share
 * between threads.
 *
 * <p>{@code ContainerSpec} is purely configuration — it does not perform Docker lifecycle behavior.
 *
 * <pre>{@code
 * ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
 *         .exposePorts(8080)
 *         .waitForContainerPort(8080)
 *         .waitForLogMessage("started")
 *         .build();
 * try (Container container = Container.create(containerSpec)) {
 *     // ... use container ...
 * }
 * }</pre>
 */
public final class ContainerSpec {

    static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private final String image;
    private final List<String> command;
    private final List<Integer> exposedPorts;
    private final List<BindMount> bindMounts;
    private final String networkMode;
    private final List<String> networkAliases;
    private final String workingDirectory;
    private final Consumer<String> logConsumer;
    private final Duration startupTimeout;
    private final int startupAttempts;
    private final List<WaitCondition> waitConditions;
    private final List<Ulimit> ulimits;
    private final long memory;
    private final long memorySwap;
    private final long shmSize;
    private final int cpuShares;
    private final long cpuPeriod;
    private final long cpuQuota;

    /**
     * Creates an immutable container specification from a builder.
     *
     * @param builder the builder with all configuration values set
     */
    private ContainerSpec(Builder builder) {
        this.image = builder.image;
        this.command = List.copyOf(builder.commandParts);
        this.exposedPorts = List.copyOf(builder.portSpecs);
        this.bindMounts = List.copyOf(builder.bindTargets);
        this.networkMode = builder.networkMode;
        this.networkAliases = List.copyOf(builder.networkAliases);
        this.workingDirectory = builder.workingDirectory;
        this.logConsumer = builder.logConsumer;
        this.startupTimeout = builder.startupTimeout;
        this.startupAttempts = builder.startupAttempts;
        this.waitConditions = List.copyOf(builder.waitConditions);
        this.ulimits = List.copyOf(builder.ulimits);
        this.memory = builder.memory;
        this.memorySwap = builder.memorySwap;
        this.shmSize = builder.shmSize;
        this.cpuShares = builder.cpuShares;
        this.cpuPeriod = builder.cpuPeriod;
        this.cpuQuota = builder.cpuQuota;
    }

    /**
     * Returns the Docker image name.
     *
     * @return the Docker image name
     */
    public String image() {
        return image;
    }

    /**
     * Returns the container entrypoint arguments.
     *
     * @return the container entrypoint arguments (unmodifiable)
     */
    public List<String> command() {
        return command;
    }

    /**
     * Returns the container ports to expose.
     *
     * @return the container ports to expose (unmodifiable)
     */
    public List<Integer> exposedPorts() {
        return exposedPorts;
    }

    /**
     * Returns the host-to-container bind mounts.
     *
     * @return the host-to-container bind mounts (unmodifiable)
     */
    public List<BindMount> bindMounts() {
        return bindMounts;
    }

    /**
     * Returns the Docker network name.
     *
     * @return the Docker network name, or {@code null}
     */
    public String networkMode() {
        return networkMode;
    }

    /**
     * Returns DNS aliases within the network.
     *
     * @return DNS aliases within the network (unmodifiable)
     */
    public List<String> networkAliases() {
        return networkAliases;
    }

    /**
     * Returns the in-container working directory.
     *
     * @return the in-container working directory, or {@code null}
     */
    public String workingDirectory() {
        return workingDirectory;
    }

    /**
     * Returns the log consumer.
     *
     * @return the log consumer, or {@code null}
     */
    public Consumer<String> logConsumer() {
        return logConsumer;
    }

    /**
     * Returns the startup timeout.
     *
     * @return the startup timeout
     */
    public Duration startupTimeout() {
        return startupTimeout;
    }

    /**
     * Returns the number of startup attempts.
     *
     * @return the number of startup attempts
     */
    public int startupAttempts() {
        return startupAttempts;
    }

    /**
     * Returns the wait conditions.
     *
     * @return the wait conditions (unmodifiable)
     */
    public List<WaitCondition> waitConditions() {
        return waitConditions;
    }

    /**
     * Returns the Linux resource limits.
     *
     * @return the Linux resource limits (unmodifiable)
     */
    public List<Ulimit> ulimits() {
        return ulimits;
    }

    /**
     * Returns the container memory limit.
     *
     * @return the container memory limit in bytes, or 0 for no explicit limit
     */
    public long memory() {
        return memory;
    }

    /**
     * Returns the total memory limit.
     *
     * @return the total memory limit (memory + swap) in bytes, or 0 for unlimited
     */
    public long memorySwap() {
        return memorySwap;
    }

    /**
     * Returns the size of {@code /dev/shm}.
     *
     * @return the size of {@code /dev/shm} in bytes, or 0 for Docker default
     */
    public long shmSize() {
        return shmSize;
    }

    /**
     * Returns the CPU share weight.
     *
     * @return the CPU share weight, or 0 for no explicit limit
     */
    public int cpuShares() {
        return cpuShares;
    }

    /**
     * Returns the CPU CFS period.
     *
     * @return the CPU CFS period in microseconds, or 0 for no explicit limit
     */
    public long cpuPeriod() {
        return cpuPeriod;
    }

    /**
     * Returns the CPU CFS quota.
     *
     * @return the CPU CFS quota in microseconds, or 0 for no explicit limit
     */
    public long cpuQuota() {
        return cpuQuota;
    }

    /**
     * Creates a new builder for the given image.
     *
     * @param image the Docker image name; must not be blank
     * @return a mutable builder
     * @throws IllegalArgumentException if {@code image} is blank
     */
    public static Builder builder(String image) {
        return new Builder(image);
    }

    /**
     * Mutable builder for configuring a {@link ContainerSpec}.
     *
     * <p>Builders are created via {@link ContainerSpec#builder(String)} and used in a fluent style:
     *
     * <pre>{@code
     * ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
     *         .exposePorts(8080, 9090)
     *         .network(network, "exporter")
     *         .waitForLogMessage("started")
     *         .startupTimeout(Duration.ofSeconds(90))
     *         .build();
     * }</pre>
     *
     * <p>Instances are mutable and not thread-safe; do not share a builder across threads.
     */
    public static final class Builder {

        private static final int MIN_CONTAINER_PORT = 1;
        private static final int MAX_CONTAINER_PORT = 65535;

        private final String image;
        private final List<String> commandParts = new ArrayList<>();
        private final List<Integer> portSpecs = new ArrayList<>();
        private final List<BindMount> bindTargets = new ArrayList<>();
        private final List<String> networkAliases = new ArrayList<>();
        private final List<WaitCondition> waitConditions = new ArrayList<>();
        private final List<Ulimit> ulimits = new ArrayList<>();

        private String networkMode;
        private String workingDirectory;
        private Consumer<String> logConsumer;
        private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;
        private int startupAttempts = 1;
        private long memory;
        private long memorySwap;
        private long shmSize;
        private int cpuShares;
        private long cpuPeriod;
        private long cpuQuota;

        /**
         * Creates a builder for the given image.
         *
         * @param image the Docker image name; must not be blank
         * @throws IllegalArgumentException if {@code image} is blank
         */
        private Builder(String image) {
            this.image = requireNonBlank(image, "image");
        }

        /**
         * Appends entrypoint arguments to the container command. May be called multiple times; arguments
         * accumulate in call order. Parts become Docker {@code CMD} arguments.
         *
         * @param parts the command arguments; must not be {@code null} or contain {@code null} elements;
         *     empty strings are allowed because Docker command arguments may intentionally be empty
         * @return this builder
         * @throws IllegalArgumentException if {@code parts} is {@code null} or contains a {@code null}
         *     element
         */
        public Builder command(String... parts) {
            if (parts == null) {
                throw new IllegalArgumentException("parts must not be null");
            }
            for (String part : parts) {
                if (part == null) {
                    throw new IllegalArgumentException("command parts must not contain null");
                }
                commandParts.add(part);
            }
            return this;
        }

        /**
         * Exposes the given container ports, mapping each to an ephemeral host port. Duplicate ports are
         * ignored.
         *
         * @param ports the container ports to expose; must not be {@code null} or contain values outside
         *     the range {@code 1..65535}
         * @return this builder
         * @throws IllegalArgumentException if {@code ports} is {@code null} or contains a value outside
         *     {@code 1..65535}
         */
        public Builder exposePorts(int... ports) {
            if (ports == null) {
                throw new IllegalArgumentException("ports must not be null");
            }
            for (int port : ports) {
                requireContainerPort(port, "port");
                if (!portSpecs.contains(port)) {
                    portSpecs.add(port);
                }
            }
            return this;
        }

        /**
         * Joins the container to a Docker network with the given DNS aliases. Calling this method more
         * than once replaces the previously selected network and aliases.
         *
         * @param network the network to join; must not be {@code null}
         * @param aliases DNS aliases within the network; must not be {@code null} or contain {@code null}
         *     or blank elements
         * @return this builder
         * @throws NullPointerException if {@code network} is {@code null}
         * @throws IllegalArgumentException if {@code aliases} is {@code null}, or if {@code aliases}
         *     contains a {@code null} or blank element
         */
        public Builder network(Network network, String... aliases) {
            Objects.requireNonNull(network, "network");
            this.networkMode = network.name();
            if (aliases == null) {
                throw new IllegalArgumentException("aliases must not be null");
            }
            networkAliases.clear();
            for (String alias : aliases) {
                networkAliases.add(requireNonBlankAlias(alias));
            }
            return this;
        }

        /**
         * Sets the working directory inside the container.
         *
         * @param directory the working directory; must not be {@code null} or blank
         * @return this builder
         * @throws NullPointerException if {@code directory} is {@code null}
         * @throws IllegalArgumentException if {@code directory} is blank
         */
        public Builder workingDirectory(String directory) {
            Objects.requireNonNull(directory, "directory");
            if (directory.isBlank()) {
                throw new IllegalArgumentException("directory must not be blank");
            }
            this.workingDirectory = directory;
            return this;
        }

        /**
         * Sets the consumer that receives non-blank, newline-stripped log lines. Typically a
         * {@link PrefixConsumer} instance.
         *
         * @param logger the log consumer; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code logger} is {@code null}
         */
        public Builder logConsumer(Consumer<String> logger) {
            this.logConsumer = Objects.requireNonNull(logger, "logger");
            return this;
        }

        /**
         * Sets the per-attempt readiness timeout. Validated when
         * {@link Container#create(ContainerSpec)} is called.
         *
         * @param duration the readiness timeout per attempt
         * @return this builder
         */
        public Builder startupTimeout(Duration duration) {
            this.startupTimeout = duration;
            return this;
        }

        /**
         * Number of startup attempts. A value of {@code 1} (the default) disables retries; higher values
         * retry on {@link ContainerException} with a linear backoff between attempts. Validated when
         * {@link Container#create(ContainerSpec)} is called.
         *
         * @param attempts the total number of startup attempts
         * @return this builder
         */
        public Builder startupAttempts(int attempts) {
            this.startupAttempts = attempts;
            return this;
        }

        /**
         * Bind-mounts an existing host directory into the container.
         *
         * @param hostPath the absolute host path; must not be blank
         * @param containerPath the absolute in-container path; must not be blank
         * @return this builder
         * @throws IllegalArgumentException if either path is blank
         */
        public Builder bindDirectory(String hostPath, String containerPath) {
            bindTargets.add(new BindMount(
                    requireNonBlank(hostPath, "hostPath"), requireNonBlank(containerPath, "containerPath")));
            return this;
        }

        /**
         * Adds a ulimit to the container configuration. May be called multiple times; ulimits
         * accumulate in call order. Duplicate names are allowed.
         *
         * @param name the ulimit name (e.g., {@code "nofile"}); must not be blank
         * @param soft the soft limit; must be {@code >= 0}
         * @param hard the hard limit; must be {@code >= 0}
         * @return this builder
         * @throws IllegalArgumentException if any parameter is invalid
         */
        public Builder ulimit(String name, long soft, long hard) {
            ulimits.add(new Ulimit(name, soft, hard));
            return this;
        }

        /**
         * Clears all configured ulimits.
         *
         * @return this builder
         */
        public Builder clearUlimits() {
            ulimits.clear();
            return this;
        }

        /**
         * Waits until the mapped host port for the given container port accepts TCP connections before
         * considering the container ready. Callers must also expose the port via
         * {@link #exposePorts(int...)} if they expect host-port probing to succeed.
         *
         * @param containerPort the container port to wait for; must be in the range {@code 1..65535}
         * @return this builder
         * @throws IllegalArgumentException if {@code containerPort} is outside {@code 1..65535}
         */
        public Builder waitForContainerPort(int containerPort) {
            requireContainerPort(containerPort, "containerPort");
            waitConditions.add(new WaitCondition.PortWait(containerPort));
            return this;
        }

        /**
         * Waits until a log line matching the given whole-string regex (with
         * {@link java.util.regex.Pattern#DOTALL}) has been emitted once. Matching is performed against
         * newline-terminated raw log lines using whole-string ({@code Matcher.matches()}) semantics.
         *
         * @param regex the regular expression; must not be blank
         * @return this builder
         * @throws IllegalArgumentException if {@code regex} is blank
         */
        public Builder waitForLogMessage(String regex) {
            waitConditions.add(new WaitCondition.LogWait(requireNonBlank(regex, "regex"), 1));
            return this;
        }

        /**
         * Waits until log lines matching the given whole-string regex (with
         * {@link java.util.regex.Pattern#DOTALL}) have been emitted the given number of times. Matching
         * is performed against newline-terminated raw log lines using whole-string
         * ({@code Matcher.matches()}) semantics.
         *
         * @param regex the regular expression; must not be blank
         * @param times the number of matching lines required; must be {@code >= 1}
         * @return this builder
         * @throws IllegalArgumentException if {@code regex} is blank or {@code times} is {@code < 1}
         */
        public Builder waitForLogMessage(String regex, int times) {
            if (times < 1) {
                throw new IllegalArgumentException("times must be >= 1, was " + times);
            }
            waitConditions.add(new WaitCondition.LogWait(requireNonBlank(regex, "regex"), times));
            return this;
        }

        private Builder waitForHttpResponseInternal(
                Protocol protocol, int containerPort, String path, int minStatus, int maxStatus) {
            requireContainerPort(containerPort, "containerPort");
            requireNonBlank(path, "path");
            if (minStatus < 100 || minStatus > 599) {
                throw new IllegalArgumentException("minStatus must be in 100..599, was " + minStatus);
            }
            if (maxStatus < 100 || maxStatus > 599) {
                throw new IllegalArgumentException("maxStatus must be in 100..599, was " + maxStatus);
            }
            if (minStatus > maxStatus) {
                throw new IllegalArgumentException(
                        "minStatus must be <= maxStatus, was " + minStatus + " > " + maxStatus);
            }
            waitConditions.add(new WaitCondition.HttpWait(containerPort, path, protocol, minStatus, maxStatus));
            return this;
        }

        /**
         * Waits until an HTTP GET against the mapped host port for {@code containerPort} and
         * {@code path} returns a status in the inclusive default range
         * {@code 200..399} before considering the container ready.
         *
         * <p>Equivalent to {@code waitForHttpResponse(containerPort, path, 200, 399)}.
         * The port must also be exposed via {@link #exposePorts(int...)}.
         *
         * @param containerPort the container port to probe; {@code 1..65535}
         * @param path the request path; must not be blank (normalized to begin with {@code /})
         * @return this builder
         * @throws IllegalArgumentException if {@code containerPort} is outside {@code 1..65535}
         *     or {@code path} is blank
         */
        public Builder waitForHttpResponse(int containerPort, String path) {
            return waitForHttpResponse(
                    containerPort,
                    path,
                    WaitCondition.HttpWait.DEFAULT_MIN_STATUS,
                    WaitCondition.HttpWait.DEFAULT_MAX_STATUS);
        }

        /**
         * Waits until an HTTP GET against the mapped host port for {@code containerPort} and
         * {@code path} returns a status in the inclusive range {@code [minStatus, maxStatus]}
         * before considering the container ready.
         *
         * <p>Unlike {@link #waitForContainerPort(int)}, this requires the service to actually
         * serve an HTTP response, avoiding false positives from Docker binding the published
         * host port before the in-container process is ready. The port must also be exposed
         * via {@link #exposePorts(int...)}.
         *
         * @param containerPort the container port to probe; {@code 1..65535}
         * @param path the request path; must not be blank (normalized to begin with {@code /})
         * @param minStatus inclusive lower bound for an acceptable status; {@code 100..599}
         * @param maxStatus inclusive upper bound for an acceptable status; {@code 100..599},
         *     and {@code >= minStatus}
         * @return this builder
         * @throws IllegalArgumentException if {@code containerPort} is outside {@code 1..65535},
         *     {@code path} is blank, either status is outside {@code 100..599}, or
         *     {@code minStatus > maxStatus}
         */
        public Builder waitForHttpResponse(int containerPort, String path, int minStatus, int maxStatus) {
            return waitForHttpResponseInternal(Protocol.HTTP, containerPort, path, minStatus, maxStatus);
        }

        /**
         * Waits until an HTTPS GET against the mapped host port for {@code containerPort} and
         * {@code path} returns a status in the inclusive default range
         * {@code 200..399} before considering the container ready.
         *
         * <p>Equivalent to {@code waitForHttpsResponse(containerPort, path, 200, 399)}.
         * The port must also be exposed via {@link #exposePorts(int...)}.
         *
         * <p>The HTTPS request uses the JVM's default SSL/TLS configuration. For services
         * using self-signed certificates, additional SSL context configuration would be required
         * (not supported by this method).
         *
         * @param containerPort the container port to probe; {@code 1..65535}
         * @param path the request path; must not be blank (normalized to begin with {@code /})
         * @return this builder
         * @throws IllegalArgumentException if {@code containerPort} is outside {@code 1..65535}
         *     or {@code path} is blank
         */
        public Builder waitForHttpsResponse(int containerPort, String path) {
            return waitForHttpsResponse(
                    containerPort,
                    path,
                    WaitCondition.HttpWait.DEFAULT_MIN_STATUS,
                    WaitCondition.HttpWait.DEFAULT_MAX_STATUS);
        }

        /**
         * Waits until an HTTPS GET against the mapped host port for {@code containerPort} and
         * {@code path} returns a status in the inclusive range {@code [minStatus, maxStatus]}
         * before considering the container ready.
         *
         * <p>Unlike {@link #waitForContainerPort(int)}, this requires the service to actually
         * serve an HTTPS response, avoiding false positives from Docker binding the published
         * host port before the in-container process is ready. The port must also be exposed
         * via {@link #exposePorts(int...)}.
         *
         * <p>The HTTPS request uses the JVM's default SSL/TLS configuration. For services
         * using self-signed certificates, additional SSL context configuration would be required
         * (not supported by this method).
         *
         * @param containerPort the container port to probe; {@code 1..65535}
         * @param path the request path; must not be blank (normalized to begin with {@code /})
         * @param minStatus inclusive lower bound for an acceptable status; {@code 100..599}
         * @param maxStatus inclusive upper bound for an acceptable status; {@code 100..599},
         *     and {@code >= minStatus}
         * @return this builder
         * @throws IllegalArgumentException if {@code containerPort} is outside {@code 1..65535},
         *     {@code path} is blank, either status is outside {@code 100..599}, or
         *     {@code minStatus > maxStatus}
         */
        public Builder waitForHttpsResponse(int containerPort, String path, int minStatus, int maxStatus) {
            return waitForHttpResponseInternal(Protocol.HTTPS, containerPort, path, minStatus, maxStatus);
        }

        /**
         * Sets the container memory limit. Applied via {@code HostConfig.withMemory()}.
         *
         * @param bytes memory limit in bytes; must be {@code >= 0} (0 = no explicit limit)
         * @return this builder
         * @throws IllegalArgumentException if {@code bytes} is negative
         */
        public Builder memory(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("memory must be >= 0, was " + bytes);
            }
            this.memory = bytes;
            return this;
        }

        /**
         * Sets the total memory limit (memory + swap). Applied via {@code HostConfig.withMemorySwap()}.
         *
         * @param bytes total memory limit in bytes; must be {@code >= 0} (0 = unlimited)
         * @return this builder
         * @throws IllegalArgumentException if {@code bytes} is negative
         */
        public Builder memorySwap(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("memorySwap must be >= 0, was " + bytes);
            }
            this.memorySwap = bytes;
            return this;
        }

        /**
         * Sets the size of {@code /dev/shm}. Applied via {@code HostConfig.withShmSize()}.
         *
         * @param bytes shm size in bytes; must be {@code >= 0} (0 = Docker default)
         * @return this builder
         * @throws IllegalArgumentException if {@code bytes} is negative
         */
        public Builder shmSize(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("shmSize must be >= 0, was " + bytes);
            }
            this.shmSize = bytes;
            return this;
        }

        /**
         * Sets the CPU share weight. Applied via {@code HostConfig.withCpuShares()}.
         *
         * @param shares CPU shares (relative weight); must be {@code >= 0} (0 = no explicit limit)
         * @return this builder
         * @throws IllegalArgumentException if {@code shares} is negative
         */
        public Builder cpuShares(int shares) {
            if (shares < 0) {
                throw new IllegalArgumentException("cpuShares must be >= 0, was " + shares);
            }
            this.cpuShares = shares;
            return this;
        }

        /**
         * Sets the CPU CFS period in microseconds. Applied via {@code HostConfig.withCpuPeriod()}.
         *
         * @param period CPU period in microseconds; must be {@code >= 0} (0 = no explicit limit)
         * @return this builder
         * @throws IllegalArgumentException if {@code period} is negative
         */
        public Builder cpuPeriod(long period) {
            if (period < 0) {
                throw new IllegalArgumentException("cpuPeriod must be >= 0, was " + period);
            }
            this.cpuPeriod = period;
            return this;
        }

        /**
         * Sets the CPU CFS quota in microseconds. Applied via {@code HostConfig.withCpuQuota()}.
         *
         * @param quota CPU quota in microseconds; must be {@code >= 0} (0 = no explicit limit)
         * @return this builder
         * @throws IllegalArgumentException if {@code quota} is negative
         */
        public Builder cpuQuota(long quota) {
            if (quota < 0) {
                throw new IllegalArgumentException("cpuQuota must be >= 0, was " + quota);
            }
            this.cpuQuota = quota;
            return this;
        }

        /**
         * Builds an immutable {@link ContainerSpec} from this builder's configuration.
         *
         * @return a new immutable container specification
         */
        public ContainerSpec build() {
            return new ContainerSpec(this);
        }
    }

    /**
     * Throws {@link IllegalArgumentException} if the given value is blank.
     *
     * @param value the value to check
     * @param name the argument name used in the error message
     * @return the value, if non-blank
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Validates that a port is within the range {@code 1..65535}.
     *
     * @param port the port value
     * @param name the argument name used in the error message
     * @return the validated port
     * @throws IllegalArgumentException if {@code port} is outside {@code 1..65535}
     */
    private static int requireContainerPort(int port, String name) {
        if (port < Builder.MIN_CONTAINER_PORT || port > Builder.MAX_CONTAINER_PORT) {
            throw new IllegalArgumentException(name + " must be in 1..65535, was " + port);
        }
        return port;
    }

    /**
     * Validates that a network alias is non-null and non-blank.
     *
     * @param alias the alias value
     * @return the validated alias
     * @throws IllegalArgumentException if {@code alias} is {@code null} or blank
     */
    private static String requireNonBlankAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        return alias;
    }
}
