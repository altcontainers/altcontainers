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
import java.util.List;
import java.util.Map;
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
public interface ContainerSpec {

    /**
     * Default startup timeout of 60 seconds.
     */
    Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Returns the Docker image name.
     *
     * @return the Docker image name
     */
    String image();

    /**
     * Returns the container entrypoint arguments.
     *
     * @return the container entrypoint arguments (unmodifiable)
     */
    List<String> command();

    /**
     * Returns the container ports to expose.
     *
     * @return the container ports to expose (unmodifiable)
     */
    List<Integer> exposedPorts();

    /**
     * Returns the host-to-container bind mounts.
     *
     * @return the host-to-container bind mounts (unmodifiable)
     */
    List<BindMount> bindMounts();

    /**
     * Returns the Docker network name.
     *
     * @return the Docker network name, or {@code null}
     */
    String networkMode();

    /**
     * Returns DNS aliases within the network.
     *
     * @return DNS aliases within the network (unmodifiable)
     */
    List<String> networkAliases();

    /**
     * Returns the in-container working directory.
     *
     * @return the in-container working directory, or {@code null}
     */
    String workingDirectory();

    /**
     * Returns the log consumer.
     *
     * @return the log consumer, or {@code null}
     */
    Consumer<String> logConsumer();

    /**
     * Returns a consumer invoked after the container starts but before readiness
     * checks begin. The {@link Container} handle has a valid {@code hostPort} at
     * this point. The default is a no-op consumer.
     *
     * <p>Throwing from the consumer cancels startup; the exception is wrapped in
     * {@link ContainerException} and follows the usual retry/destroy-on-failure
     * behavior.
     *
     * @return the startup consumer, never {@code null}
     */
    default Consumer<Container> startupConsumer() {
        return container -> {};
    }

    /**
     * Returns the startup timeout.
     *
     * @return the startup timeout
     */
    Duration startupTimeout();

    /**
     * Returns the number of startup attempts.
     *
     * @return the number of startup attempts
     */
    int startupAttempts();

    /**
     * Returns the wait strategies.
     *
     * @return the wait strategies (unmodifiable)
     */
    List<WaitStrategy> waitConditions();

    /**
     * Returns the Linux resource limits.
     *
     * @return the Linux resource limits (unmodifiable)
     */
    List<Ulimit> ulimits();

    /**
     * Returns the container memory limit.
     *
     * @return the container memory limit in bytes, or 0 for no explicit limit
     */
    long memory();

    /**
     * Returns the total memory limit.
     *
     * @return the total memory limit (memory + swap) in bytes, or 0 for unlimited
     */
    long memorySwap();

    /**
     * Returns the size of {@code /dev/shm}.
     *
     * @return the size of {@code /dev/shm} in bytes, or 0 for Docker default
     */
    long shmSize();

    /**
     * Returns the CPU share weight.
     *
     * @return the CPU share weight, or 0 for no explicit limit
     */
    int cpuShares();

    /**
     * Returns the CPU CFS period.
     *
     * @return the CPU CFS period in microseconds, or 0 for no explicit limit
     */
    long cpuPeriod();

    /**
     * Returns the CPU CFS quota.
     *
     * @return the CPU CFS quota in microseconds, or 0 for no explicit limit
     */
    long cpuQuota();

    /**
     * Returns the container environment variables.
     *
     * @return the container environment variables (unmodifiable, empty by default)
     */
    Map<String, String> environment();

    /**
     * Returns the fixed host port bindings, mapping container ports to host ports.
     * An empty map (the default) means Docker assigns random host ports for every exposed port.
     *
     * @return the port bindings (unmodifiable, containerPort → hostPort)
     */
    default Map<Integer, Integer> portBindings() {
        return Map.of();
    }

    /**
     * Creates a new builder for the given image.
     *
     * @param image the Docker image name; must not be blank
     * @return a mutable builder
     * @throws IllegalArgumentException if {@code image} is blank
     */
    static GenericContainerSpec.Builder builder(String image) {
        return new GenericContainerSpec.Builder(image);
    }
}
