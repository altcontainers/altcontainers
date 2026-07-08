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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;

/**
 * Network lifecycle facade — uses docker-java directly.
 */
public final class NetworkManager {

    private static final NetworkManager INSTANCE = new NetworkManager();

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
     *
     * @param network the network to close; may be {@code null}
     */
    public void closeNetwork(Network network) {
        if (network == null) {
            return;
        }
        try {
            DockerClientFactory.client().removeNetworkCmd(network.id()).exec();
        } catch (RuntimeException e) {
            // Best-effort
        } finally {
            if (networkSemaphore != null && releasedIds.putIfAbsent(network.id(), Boolean.TRUE) == null) {
                networkSemaphore.release();
                releasedIds.remove(network.id());
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
