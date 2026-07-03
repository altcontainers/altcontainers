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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import nonapi.org.altcontainers.reaper.ResourceController;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;

/**
 * Public lifecycle facade for creating and destroying Docker networks.
 *
 * <p>{@code NetworkManager} is the primary entry point for network lifecycle operations. The singleton
 * delegates to static operation utilities backed by {@link DockerClient} for all Docker daemon communication.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try (Network network = NetworkManager.getInstance().createNetwork()) {
 *     ContainerSpec containerSpec = ContainerSpec.builder(image)
 *             .network(network, "application")
 *             .build();
 *     try (Container container = ContainerManager.getInstance().createContainer(spec)) {
 *         // ... test ...
 *     }
 * }
 * }</pre>
 */
public final class NetworkManager {

    /**
     * The singleton instance, backed by Docker.
     */
    private static final NetworkManager INSTANCE = new NetworkManager(DockerClient.instance());

    /**
     * The Docker execution backend.
     */
    private final DockerClient client;

    /**
     * Limits the number of concurrently active Docker bridge networks.
     * <p>A value of 0 means unlimited (no gating). Positive values bound
     * the number of simultaneous {@link #createNetwork()} calls that can
     * proceed before callers block. Initialized from the
     * {@code altcontainers.environments.parallelism} system property.
     * <p>{@code null} when the parallelism property is 0 or unset.
     */
    private final Semaphore networkSemaphore;

    /**
     * Tracks which network ids have already had their semaphore permit released.
     * Prevents double-release when {@link #destroyNetwork(Network)} is called
     * idempotently on the same network. Only populated when {@code networkSemaphore}
     * is non-null.
     */
    private final ConcurrentHashMap<String, Boolean> releasedSemaphoreNetworkIds = new ConcurrentHashMap<>();

    /**
     * Creates a network manager backed by the given Docker client.
     *
     * <p>Reads the {@code altcontainers.environments.parallelism} system property
     * to initialize the network semaphore.
     *
     * @param client the Docker client; must not be {@code null}
     */
    NetworkManager(DockerClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        int networkParallelism = readNetworkParallelism(System.getProperty("altcontainers.environments.parallelism"));
        this.networkSemaphore = networkParallelism > 0 ? new Semaphore(networkParallelism) : null;
    }

    /**
     * Package-private constructor for tests that need a predetermined semaphore.
     *
     * @param client the Docker client; must not be null
     * @param networkSemaphore the semaphore to use; may be null for unlimited
     */
    NetworkManager(DockerClient client, Semaphore networkSemaphore) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.networkSemaphore = networkSemaphore;
    }

    /**
     * Returns the shared singleton {@link NetworkManager}.
     *
     * @return the singleton network manager instance
     */
    public static NetworkManager getInstance() {
        return INSTANCE;
    }

    /**
     * Creates a new Docker bridged network with a readable name derived from the reaper session.
     *
     * @return a runtime handle to the created network
     * @throws ContainerException if Docker fails to create the network or the reaper is unavailable
     */
    public Network createNetwork() {
        if (networkSemaphore != null) {
            try {
                networkSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for network creation permit", e);
            }
        }
        try {
            var controller = ResourceController.instance();
            var session = controller.ensureReady();
            var labels = session.labelsForNewResource();
            String sessionPart = session.sessionId().substring(0, 8);
            String randomPart = UUID.randomUUID().toString().substring(0, 8);
            String name = "altcontainers-" + sessionPart + "-" + randomPart;
            String id = NetworkOperations.createNetwork(client, name, labels);
            return new Network(name, id);
        } catch (Throwable t) {
            if (networkSemaphore != null) {
                networkSemaphore.release();
            }
            throw t;
        }
    }

    /**
     * Destroys a Docker network, blocking until Docker confirms it is gone.
     *
     * <p>Idempotent: destroying an already-destroyed network is safe. Passing {@code null} is a
     * no-op. The network cannot be reused after this call.
     *
     * @param network the network to destroy, or {@code null} for a no-op
     * @throws ContainerException if the network is not confirmed destroyed within the destroy deadline
     */
    public void destroyNetwork(Network network) {
        if (network == null) {
            return;
        }
        try {
            client.destroyNetwork(network.id());
        } finally {
            if (networkSemaphore != null
                    && releasedSemaphoreNetworkIds.putIfAbsent(network.id(), Boolean.TRUE) == null) {
                networkSemaphore.release();
            }
        }
    }

    /**
     * Package-private for testing. Parses the raw system-property value.
     *
     * @param raw the raw property value from {@code System.getProperty}, may be null
     * @return the parallelism level; 0 means unlimited
     * @throws ContainerException if the value is present and non-blank but not a
     *     non-negative integer
     */
    static int readNetworkParallelism(String raw) {
        if (raw == null) {
            return 0;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new ContainerException(
                    "altcontainers.environments.parallelism must be a non-negative integer, was: " + raw, e);
        }
        if (value < 0) {
            throw new ContainerException(
                    "altcontainers.environments.parallelism must be a non-negative integer, was: " + raw);
        }
        return value;
    }
}
