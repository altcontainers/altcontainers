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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network lifecycle facade — uses docker-java directly.
 */
public final class NetworkManager {

    private static final NetworkManager INSTANCE = new NetworkManager();

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkManager.class);
    private static final ExecutorService STOP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "altcontainers-network-remove");
        t.setDaemon(true);
        return t;
    });

    private final Semaphore networkSemaphore;
    private final ConcurrentHashMap<String, Boolean> releasedIds = new ConcurrentHashMap<>();

    private NetworkManager() {
        int p = readNetworkParallelism(System.getProperty(
                "altcontainers.reaper.networks.parallelism", System.getProperty("altcontainers.networks.parallelism")));
        this.networkSemaphore = p > 0 ? new Semaphore(p) : null;
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
        ctrl.ensureReady();
        if (networkSemaphore != null) {
            try {
                networkSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted");
            }
        }
        try {
            var session = ReaperController.instance().ensureReady();
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
                return new ConcreteNetwork(name, response.getId());
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
        long stopTimeoutSeconds =
                ReaperController.instance().configuration().reaperStopTimeout().toSeconds();
        // Future timeout includes a buffer so Docker daemon latency doesn't
        // race the Java timeout, avoiding unnecessary delegation to the reaper.
        long futureTimeoutSeconds = Math.addExact(stopTimeoutSeconds, 10L);
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> DockerClientFactory.client()
                            .removeNetworkCmd(network.id())
                            .exec(),
                    STOP_EXECUTOR);
            future.get(futureTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.debug(
                    "Remove timed out for network {} after {}s, delegating to reaper",
                    network.id(),
                    futureTimeoutSeconds);
            delegateTerminateNetworkToReaper(network.id());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof com.github.dockerjava.api.exception.NotFoundException) {
                LOGGER.debug("Network {} not found during remove (already removed)", network.id());
            } else {
                LOGGER.debug(
                        "Remove failed for network {}, delegating to reaper: {}",
                        network.id(),
                        e.getCause() != null ? e.getCause().getMessage() : "unknown");
                delegateTerminateNetworkToReaper(network.id());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Interrupted during remove for network {}, delegating to reaper", network.id());
            delegateTerminateNetworkToReaper(network.id());
        } finally {
            if (networkSemaphore != null && releasedIds.putIfAbsent(network.id(), Boolean.TRUE) == null) {
                networkSemaphore.release();
                releasedIds.remove(network.id());
            }
        }
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

    /**
     * Reads the network parallelism value from a system property.
     * A value of 0 means no limit.
     *
     * @param raw the raw system property value, or {@code null}
     * @return the parallelism value, or 0 if unset
     * @throws ContainerException if the value is negative or not a valid integer
     */
    static int readNetworkParallelism(String raw) {
        if (raw == null) {
            return 0;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return 0;
        }
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new ContainerException("parallelism must be a non-negative integer", e);
        }
        if (v < 0) {
            throw new ContainerException("parallelism must be non-negative");
        }
        return v;
    }
}
