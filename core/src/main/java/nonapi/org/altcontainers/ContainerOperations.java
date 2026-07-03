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
import nonapi.org.altcontainers.docker.DockerContainerInspect;
import nonapi.org.altcontainers.docker.DockerNotFoundException;
import org.altcontainers.api.ContainerException;

/**
 * Container-related Docker operations.
 *
 * <p>Package-private static utility. Every method takes {@link DockerClient} as the first argument
 * to access the configured Docker HTTP client. Methods are pure delegation + error wrapping — no
 * log-stream tracking or cross-cutting orchestration.
 */
public final class ContainerOperations {

    /**
     * Maximum time, in seconds, to wait for a graceful container stop before force-removing.
     */
    private static final int STOP_TIMEOUT_SECONDS = 2;

    private ContainerOperations() {
        // Intentionally empty
    }

    /**
     * Creates, but does not start, a Docker container from the given specification.
     *
     * @param client the Docker client
     * @param spec the container specification; must not be {@code null}
     * @return the newly created container's identifier
     * @throws IllegalArgumentException if {@code spec} is {@code null}
     * @throws ContainerException if Docker refuses or fails the create request
     */
    public static String createContainer(DockerClient client, ContainerCreateSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        try {
            return client.delegate().createContainer(spec);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to create container for image: " + spec.image(), e);
        }
    }

    /**
     * Starts a previously created container.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if Docker fails to start the container
     */
    public static void startContainer(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            client.delegate().startContainer(id);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to start container " + id, e);
        }
    }

    /**
     * Returns whether a container with the given identifier currently exists.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @return {@code true} if the container exists; {@code false} if it is absent
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the existence check itself fails for a non-absence reason
     */
    public static boolean containerExists(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            client.delegate().inspectContainer(id);
            return true;
        } catch (DockerNotFoundException e) {
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
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @return {@code true} if the container exists and is running; {@code false} if it is absent
     *     or not running
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the state check itself fails for a non-absence reason
     */
    public static boolean isContainerRunning(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            return client.delegate().inspectContainer(id).running();
        } catch (DockerNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect container " + id, e);
        }
    }

    /**
     * Resolves the host port mapped to the given container port.
     *
     * <p>Returns {@code -1} for absent containers, missing port bindings, malformed port specs,
     * or invalid container port values. Only non-absence Docker inspection failures are escalated.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @param containerPort the container-side port; values outside {@code 1..65535} return
     *     {@code -1}
     * @return the mapped host port, or {@code -1} if no mapping exists, the container is gone,
     *     the port is invalid, or the port specification is malformed
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if the port lookup itself fails for a non-absence reason
     */
    public static int hostPort(DockerClient client, String id, int containerPort) {
        DockerClient.requireNonBlank(id, "id");
        try {
            return client.delegate().inspectContainer(id).hostPort(containerPort);
        } catch (DockerNotFoundException e) {
            return -1;
        } catch (RuntimeException e) {
            throw new ContainerException(
                    "Failed to resolve host port for container " + id + ", port " + containerPort, e);
        }
    }

    /**
     * Best-effort graceful stop of a container.
     *
     * <p>Absent containers are tolerated. Other failures are swallowed because a subsequent
     * force-remove will finalize destruction.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static void stopContainer(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            client.delegate().stopContainer(id, STOP_TIMEOUT_SECONDS);
        } catch (RuntimeException ignored) {
            // Best-effort graceful stop (including NotFoundException); force-remove finalizes
            // destruction.
        }
    }

    /**
     * Force-removes a container.
     *
     * <p>Idempotent: absent containers are tolerated, as are transient removal failures. Does
     * NOT close log streams — that is the responsibility of
     * {@link DockerClient#destroyContainer(String)}.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static void forceRemoveContainer(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            client.delegate().removeContainer(id, true);
        } catch (RuntimeException ignored) {
            // Transient (including NotFoundException); destruction is confirmed by the caller via
            // awaitContainerGone.
        }
    }

    /**
     * Returns the labels of a container.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @return the container labels, or an empty map if absent or unavailable
     */
    public static Map<String, String> inspectContainerLabels(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            return client.delegate().inspectContainer(id).labels();
        } catch (DockerNotFoundException e) {
            return Map.of();
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect container labels for " + id, e);
        }
    }

    /**
     * Returns a human-readable diagnostics string describing the container's terminal
     * state (OOMKilled, dead, exitCode, error), or an empty string if the container
     * appears healthy or cannot be inspected.
     *
     * <p>Best-effort: inspection failures are silently swallowed, returning an empty
     * string so that diagnostics enrichment never suppresses the original failure.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @return diagnostics such as "container was OOMKilled; exitCode=137", or empty
     */
    public static String inspectContainerDiagnostics(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            return client.delegate().inspectContainer(id).diagnostics();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Lists container ids matching the given label filter.
     *
     * @param client the Docker client
     * @param labels the label filter map (AND semantics)
     * @return the matching container ids (may be empty)
     * @throws ContainerException if the list operation fails
     */
    public static List<String> listContainerIdsByLabels(DockerClient client, Map<String, String> labels) {
        try {
            return client.delegate().listContainerIdsByLabels(labels);
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to list containers by labels", e);
        }
    }

    /**
     * Blocks until Docker has propagated port mappings for all the given container ports,
     * or until the timeout elapses.
     *
     * <p>Polls with exponential backoff (via {@link PollBackoff}) until every port in
     * {@code containerPorts} has a non-null binding. No-op when {@code containerPorts}
     * is null or empty.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @param containerPorts the container ports to wait for; null or empty is a no-op
     * @param timeout the maximum time to wait; must not be null or non-positive
     * @throws ContainerException if the port mappings are not ready within {@code timeout},
     *     or if the calling thread is interrupted while waiting
     */
    public static void awaitPortMappings(
            DockerClient client, String id, List<Integer> containerPorts, Duration timeout) {
        DockerClient.requireNonBlank(id, "id");
        if (containerPorts == null || containerPorts.isEmpty()) {
            return;
        }
        DockerClient.requirePositive(timeout, "timeout");
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        while (true) {
            if (allPortMappingsReady(client, id, containerPorts)) {
                return;
            }
            if (!PollBackoff.sleepWithBackoff(deadlineNanos, sleepMs, 0)) {
                // Re-check condition before reporting timeout — port mappings may have
                // propagated during the sleep, even when the deadline has elapsed.
                if (allPortMappingsReady(client, id, containerPorts)) {
                    return;
                }
                if (deadlineNanos - System.nanoTime() <= 0) {
                    // Clear any lingering interrupt flag from sleepWithBackoff so the
                    // caller does not observe a stale interrupt after a clean timeout.
                    Thread.interrupted();
                    break;
                }
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for port mappings for container " + id);
            }
        }
        throw new ContainerException("Port mappings for container " + id + " not ready within " + timeout);
    }

    /**
     * Blocks until the container can no longer be inspected, or until the timeout elapses.
     *
     * <p>Does not itself issue a remove; callers should first remove the container.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @param timeout the maximum time to wait; must not be {@code null} or non-positive
     * @throws IllegalArgumentException if {@code id} is blank or {@code timeout} is not positive
     * @throws ContainerException if the container is still present after {@code timeout}, or if the
     *     calling thread is interrupted while waiting
     */
    public static void awaitContainerGone(DockerClient client, String id, Duration timeout) {
        DockerClient.requireNonBlank(id, "id");
        DockerClient.requirePositive(timeout, "timeout");
        DestructionPoller.awaitAbsence("Docker container " + id, timeout, () -> !containerExists(client, id));
    }

    /**
     * Returns whether all the given container ports have non-null port bindings according
     * to a single inspect call.
     */
    private static boolean allPortMappingsReady(DockerClient client, String id, List<Integer> containerPorts) {
        try {
            DockerContainerInspect inspect = client.delegate().inspectContainer(id);
            for (int port : containerPorts) {
                if (inspect.hostPort(port) < 0) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
