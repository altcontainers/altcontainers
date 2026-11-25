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

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network lifecycle facade — uses docker-java directly.
 *
 * <p>When a parallelism limit is configured, {@link #createNetwork()} acquires a
 * semaphore permit and each successfully created network holds it until
 * {@link #closeNetwork(Network)} releases it. Closing a network that did not acquire
 * a permit through this manager does not affect the limit. Use try-with-resources on
 * {@link Network} rather than relying solely on the reaper for Docker-side cleanup,
 * which does not release permits in this JVM.
 */
public final class NetworkManager {

    private static final NetworkManager INSTANCE = new NetworkManager();

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkManager.class);
    private static final ExecutorService STOP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "altcontainers-network-remove");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean shuttingDown;

    private final Semaphore networkSemaphore;
    private final Set<String> networkIdsWithPermits = ConcurrentHashMap.newKeySet();

    private NetworkManager() {
        this(AltcontainersProperties.instance().networksParallelism());
        registerShutdownHook();
    }

    /**
     * Creates a manager with an explicit network parallelism limit, for
     * testing. Does not register a shutdown hook.
     *
     * @param networksParallelism the parallelism limit; {@code <= 0} means no limit
     */
    NetworkManager(int networksParallelism) {
        this.networkSemaphore = networksParallelism > 0 ? new Semaphore(networksParallelism) : null;
    }

    /**
     * Returns the number of available network-creation permits. Intended for
     * testing only.
     *
     * @return the available permit count, or {@code -1} if no limit is configured
     */
    int availableNetworkPermitsForTesting() {
        return networkSemaphore != null ? networkSemaphore.availablePermits() : -1;
    }

    /**
     * Registers a JVM shutdown hook that gracefully shuts down the stop executor.
     */
    private void registerShutdownHook() {
        ShutdownHooks.registerStopExecutorShutdown(
                "altcontainers-network-remove-shutdown", () -> shuttingDown = true, () -> STOP_EXECUTOR);
    }

    /**
     * Returns the singleton instance.
     *
     * @return the singleton instance
     */
    public static NetworkManager getInstance() {
        return INSTANCE;
    }

    /**
     * Creates a new Docker network via docker-java.
     *
     * @return a network handle
     * @throws ContainerException if creation fails
     */
    public Network createNetwork() {
        ReaperController ctrl = ReaperController.instance();
        ResourceSession session = ctrl.ensureReady();
        if (networkSemaphore != null) {
            try {
                networkSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted");
            }
        }
        try {
            var labels = session.labelsForNewResource();
            String name = "altcontainers-" + session.sessionId().substring(0, 8) + "-"
                    + UUID.randomUUID().toString().substring(0, 8);

            try {
                var response = DockerClientFactory.client()
                        .createNetworkCmd()
                        .withName(name)
                        .withDriver("bridge")
                        .withLabels(labels)
                        .exec();
                Network network = new ConcreteNetwork(name, response.getId());
                if (networkSemaphore != null) {
                    networkIdsWithPermits.add(network.id());
                }
                return network;
            } catch (RuntimeException e) {
                throw new ContainerException("Failed to create network: " + name, e);
            }
        } catch (Exception e) {
            if (networkSemaphore != null) {
                networkSemaphore.release();
            }
            throw e;
        }
    }

    /**
     * Destroys a Docker network via docker-java. Null-safe.
     * On timeout or failure after handling known-safe results, delegates
     * cleanup to the reaper process as a safety net.
     *
     * @param network the network to close; may be {@code null}
     */
    public void closeNetwork(Network network) {
        if (network == null) {
            return;
        }
        String networkId = network.id();
        try {
            if (shuttingDown) {
                LOGGER.debug("Shutdown in progress, delegating network {} remove to reaper", networkId);
                delegateTerminateNetworkToReaper(networkId);
                return;
            }
            removeNetwork(networkId);
        } finally {
            if (networkSemaphore != null && networkIdsWithPermits.remove(networkId)) {
                networkSemaphore.release();
            }
        }
    }

    private void removeNetwork(String networkId) {
        long stopTimeoutSeconds =
                ReaperController.instance().configuration().reaperStopTimeout().toSeconds();
        // Future timeout includes a buffer so Docker daemon latency doesn't
        // race the Java timeout, avoiding unnecessary delegation to the reaper.
        long futureTimeoutSeconds = Math.addExact(stopTimeoutSeconds, 10L);
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> DockerClientFactory.client()
                            .removeNetworkCmd(networkId)
                            .exec(),
                    STOP_EXECUTOR);
            future.get(futureTimeoutSeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Executor shut down, delegating network {} remove to reaper", networkId);
            delegateTerminateNetworkToReaper(networkId);
        } catch (TimeoutException e) {
            LOGGER.info(
                    "Remove timed out for network {} after {}s, delegating to reaper", networkId, futureTimeoutSeconds);
            delegateTerminateNetworkToReaper(networkId);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof com.github.dockerjava.api.exception.NotFoundException) {
                LOGGER.debug("Network {} not found during remove (already removed)", networkId);
            } else if (isActiveEndpointsFailure(e.getCause())) {
                // ContainerManager force-removes a container whose stop fails, so a container that
                // is still attached here was either not closed before its network, was attached
                // outside altcontainers, or could not be removed (already logged at INFO). The
                // reaper retries the removal and force-disconnects remaining endpoints.
                LOGGER.info(
                        "Network {} still has attached containers (close containers before their"
                                + " network), delegating to reaper: {}",
                        networkId,
                        e.getCause().getMessage());
                delegateTerminateNetworkToReaper(networkId);
            } else {
                LOGGER.info(
                        "Remove failed for network {}, delegating to reaper: {}",
                        networkId,
                        e.getCause() != null ? e.getCause().getMessage() : "unknown");
                delegateTerminateNetworkToReaper(networkId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Interrupted during remove for network {}, delegating to reaper", networkId);
            delegateTerminateNetworkToReaper(networkId);
        }
    }

    /**
     * Returns whether a network removal failed because containers are still attached to the
     * network. Docker reports this as {@code error while removing network: network ... has active
     * endpoints} (HTTP 403 on current daemons).
     *
     * @param cause the failure cause; may be {@code null}
     * @return {@code true} if the failure is the active-endpoints error
     */
    static boolean isActiveEndpointsFailure(Throwable cause) {
        return cause instanceof com.github.dockerjava.api.exception.DockerException
                && cause.getMessage() != null
                && cause.getMessage().contains("has active endpoints");
    }

    /**
     * Sends a TERMINATE_NETWORK command to the reaper process for
     * asynchronous network cleanup.
     *
     * @param networkId the Docker network ID
     */
    private void delegateTerminateNetworkToReaper(String networkId) {
        ReaperConnection conn = ReaperController.instance().reaperConnection();
        if (conn != null) {
            try {
                conn.sendTerminateNetwork(networkId);
            } catch (IOException e) {
                LOGGER.warn("Failed to send TERMINATE_NETWORK to reaper for network {}: {}", networkId, e.getMessage());
            }
        }
    }
}
