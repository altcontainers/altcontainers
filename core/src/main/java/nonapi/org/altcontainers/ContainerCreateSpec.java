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

import java.util.List;
import java.util.Map;
import org.altcontainers.api.BindMount;
import org.altcontainers.api.Ulimit;

/**
 * Immutable, runtime-neutral description of a container to be created.
 *
 * <p>{@code ContainerCreateSpec} gathers every resolved configuration value needed to create a container.
 * It is consumed by {@link DockerClient} (and future {@code PodmanClient}). Keeping it free of
 * runtime-specific types lets the execution layer handle conversion. Instances are immutable and safe to
 * share between threads.
 *
 * @param image the Docker image name; must not be {@code null} or blank
 * @param command the container entrypoint arguments; never {@code null}, may be empty, must not contain
 *     {@code null} elements
 * @param exposedPorts the container ports to expose; never {@code null}, may be empty, must not contain
 *     {@code null} elements, each port must be in the range {@code 1..65535}
 * @param bindMounts the host-to-container read-write bind mounts; never {@code null}, may be empty, must
 *     not contain {@code null} elements
 * @param networkMode the Docker network name to join, or {@code null} when no network is configured; when
 *     {@code null}, {@code networkAliases} must be empty
 * @param networkAliases DNS aliases within the network; never {@code null}, may be empty, must not contain
 *     {@code null} or blank elements
 * @param workingDirectory the in-container working directory, or {@code null} when unset; when non-null,
 *     must not be blank
 * @param ulimits the Linux resource limits; never {@code null}, may be empty
 * @param memory the container memory limit in bytes, or 0 for no explicit limit
 * @param memorySwap the total memory limit (memory + swap) in bytes, or 0 for unlimited
 * @param shmSize the size of {@code /dev/shm} in bytes, or 0 for Docker default
 * @param cpuShares the CPU share weight, or 0 for no explicit limit
 * @param cpuPeriod the CPU CFS period in microseconds, or 0 for no explicit limit
 * @param cpuQuota the CPU CFS quota in microseconds, or 0 for no explicit limit
 * @param labels the Docker labels to apply to the container; never {@code null}, may be empty
 */
public record ContainerCreateSpec(
        String image,
        List<String> command,
        List<Integer> exposedPorts,
        List<BindMount> bindMounts,
        String networkMode,
        List<String> networkAliases,
        String workingDirectory,
        List<Ulimit> ulimits,
        long memory,
        long memorySwap,
        long shmSize,
        int cpuShares,
        long cpuPeriod,
        long cpuQuota,
        Map<String, String> labels) {

    /**
     * Minimum valid port number.
     */
    private static final int MIN_PORT = 1;

    /**
     * Maximum valid port number.
     */
    private static final int MAX_PORT = 65535;

    /**
     * Compact canonical constructor that enforces invariants and applies defensive immutable copies.
     *
     * @param image the Docker image name; must not be {@code null} or blank
     * @param command the container entrypoint arguments; must not be {@code null} or contain {@code null}
     * @param exposedPorts the container ports to expose; must not be {@code null} or contain {@code null}
     * @param bindMounts the host-to-container bind mounts; must not be {@code null} or contain {@code null}
     * @param networkMode the Docker network name, or {@code null}
     * @param networkAliases DNS aliases within the network; must not be {@code null} or contain {@code null} or blank
     * @param workingDirectory the in-container working directory, or {@code null}
     * @param ulimits the Linux resource limits; must not be {@code null}
     * @param memory the container memory limit in bytes
     * @param memorySwap the total memory limit in bytes
     * @param shmSize the size of {@code /dev/shm} in bytes
     * @param cpuShares the CPU share weight
     * @param cpuPeriod the CPU CFS period in microseconds
     * @param cpuQuota the CPU CFS quota in microseconds
     * @param labels the Docker labels; must not be {@code null}
     * @throws IllegalArgumentException if any invariant is violated
     */
    public ContainerCreateSpec {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        requireNonNull(command, "command");
        requireNonNull(exposedPorts, "exposedPorts");
        requireNonNull(bindMounts, "bindMounts");
        requireNonNull(networkAliases, "networkAliases");
        requireNonNull(ulimits, "ulimits");

        for (String entry : command) {
            if (entry == null) {
                throw new IllegalArgumentException("command must not contain null elements");
            }
        }
        for (Integer port : exposedPorts) {
            if (port == null) {
                throw new IllegalArgumentException("exposedPorts must not contain null elements");
            }
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new IllegalArgumentException("exposedPorts must contain values in 1..65535, was " + port);
            }
        }
        for (BindMount mount : bindMounts) {
            if (mount == null) {
                throw new IllegalArgumentException("bindMounts must not contain null elements");
            }
        }
        for (String alias : networkAliases) {
            if (alias == null) {
                throw new IllegalArgumentException("networkAliases must not contain null elements");
            }
            if (alias.isBlank()) {
                throw new IllegalArgumentException("networkAliases must not contain blank elements");
            }
        }
        for (Ulimit ulimit : ulimits) {
            if (ulimit == null) {
                throw new IllegalArgumentException("ulimits must not contain null elements");
            }
        }
        if (networkMode == null && !networkAliases.isEmpty()) {
            throw new IllegalArgumentException("networkAliases must be empty when networkMode is null");
        }
        if (networkMode != null && networkMode.isBlank()) {
            throw new IllegalArgumentException("networkMode must not be blank");
        }
        if (workingDirectory != null && workingDirectory.isBlank()) {
            throw new IllegalArgumentException("workingDirectory must not be blank");
        }
        requireNonNull(labels, "labels");

        command = List.copyOf(command);
        exposedPorts = List.copyOf(exposedPorts);
        bindMounts = List.copyOf(bindMounts);
        networkAliases = List.copyOf(networkAliases);
        ulimits = List.copyOf(ulimits);
        labels = Map.copyOf(labels);
    }

    /**
     * Null-check helper used by the compact constructor.
     *
     * @param value the value to check
     * @param name the argument name used in the error message
     * @param <T> the value type
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static <T> void requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
