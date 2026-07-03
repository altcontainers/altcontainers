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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import nonapi.org.altcontainers.docker.DockerNotFoundException;
import org.altcontainers.api.ContainerException;

/**
 * Network-related Docker operations.
 *
 * <p>Package-private static utility. Every method takes {@link DockerClient} as the first argument
 * to access the configured Docker HTTP client.
 */
public final class NetworkOperations {

    /**
     * Timeout for confirming a newly created network is inspectable before returning.
     */
    private static final Duration NETWORK_READY_TIMEOUT = Duration.ofSeconds(2);

    private NetworkOperations() {
        // Intentionally empty
    }

    /**
     * Creates a Docker bridge network with the given name and labels.
     *
     * <p>After creation, blocks until the network is confirmed inspectable (up to
     * {@link #NETWORK_READY_TIMEOUT}) before returning.
     *
     * @param client the Docker client
     * @param name the network name; must not be blank
     * @param labels the Docker labels to apply; must not be {@code null}
     * @return the Docker-assigned network identifier
     * @throws IllegalArgumentException if {@code name} is blank or {@code labels} is {@code null}
     * @throws ContainerException if Docker fails to create the network, or if the network is not
     *     confirmed inspectable within the readiness timeout
     */
    public static String createNetwork(DockerClient client, String name, Map<String, String> labels) {
        DockerClient.requireNonBlank(name, "name");
        if (labels == null) {
            throw new IllegalArgumentException("labels must not be null");
        }
        String id;
        try {
            id = client.delegate().createNetwork(name, labels);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to create Docker network: " + name, e);
        }
        awaitNetworkReady(client, id, name);
        return id;
    }

    /**
     * Removes a Docker network. Idempotent: absent networks are tolerated.
     *
     * @param client the Docker client
     * @param id the network identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static void removeNetwork(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            client.delegate().removeNetwork(id);
        } catch (RuntimeException ignored) {
            // Best-effort (including NotFoundException); destroyNetwork confirms via
            // awaitNetworkGone.
        }
    }

    /**
     * Returns whether a network with the given identifier currently exists.
     *
     * @param client the Docker client
     * @param id the network identifier; must not be blank
     * @return {@code true} if the network exists; {@code false} if it is absent
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the existence check itself fails for a non-absence reason
     */
    public static boolean networkExists(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            client.delegate().inspectNetwork(id);
            return true;
        } catch (DockerNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect Docker network " + id, e);
        }
    }

    /**
     * Returns the labels of a network.
     *
     * @param client the Docker client
     * @param id the network identifier; must not be blank
     * @return the network labels, or an empty map if absent or unavailable
     */
    public static Map<String, String> inspectNetworkLabels(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            return client.delegate().inspectNetwork(id).labels();
        } catch (DockerNotFoundException e) {
            return Map.of();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect network labels for " + id, e);
        }
    }

    /**
     * Lists network ids matching the given label filter.
     *
     * @param client the Docker client
     * @param labels the label filter map
     * @return the matching network ids (may be empty)
     * @throws ContainerException if the list operation fails
     */
    public static List<String> listNetworkIdsByLabels(DockerClient client, Map<String, String> labels) {
        try {
            return client.delegate().listNetworkIdsByLabels(labels);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to list networks by labels", e);
        }
    }

    /**
     * Blocks until the given network is inspectable, or until the readiness timeout elapses.
     *
     * @param client the Docker client
     * @param id the network identifier
     * @param name the network name
     * @throws ContainerException if the network is not ready within the timeout, or if interrupted
     */
    public static void awaitNetworkReady(DockerClient client, String id, String name) {
        DockerClient.requireNonBlank(id, "id");
        DockerClient.requireNonBlank(name, "name");
        long deadlineNanos = System.nanoTime() + NETWORK_READY_TIMEOUT.toNanos();
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        while (true) {
            if (networkExists(client, id)) {
                return;
            }
            if (!PollBackoff.sleepWithBackoff(deadlineNanos, sleepMs, 0)) {
                // Re-check condition before reporting timeout — network may have become
                // ready during the sleep, even when the deadline has elapsed.
                if (networkExists(client, id)) {
                    return;
                }
                if (deadlineNanos - System.nanoTime() <= 0) {
                    break;
                }
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for network " + name + " to become ready");
            }
        }
        throw new ContainerException("Docker network " + name + " not ready within " + NETWORK_READY_TIMEOUT);
    }

    /**
     * Blocks until the network can no longer be inspected, or until the timeout elapses.
     *
     * @param client the Docker client
     * @param id the network identifier; must not be blank
     * @param timeout the maximum time to wait; must not be {@code null} or non-positive
     * @throws IllegalArgumentException if {@code id} is blank or {@code timeout} is not positive
     * @throws ContainerException if the network is still present after {@code timeout}, or if the
     *     calling thread is interrupted while waiting
     */
    public static void awaitNetworkGone(DockerClient client, String id, Duration timeout) {
        DockerClient.requireNonBlank(id, "id");
        DockerClient.requirePositive(timeout, "timeout");
        DestructionPoller.awaitAbsence("Docker network " + id, timeout, () -> !networkExists(client, id));
    }
}
