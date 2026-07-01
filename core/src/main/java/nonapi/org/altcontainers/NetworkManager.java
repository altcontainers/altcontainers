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
import nonapi.org.altcontainers.reaper.ResourceController;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;

/**
 * Public lifecycle facade for creating and destroying Docker networks.
 *
 * <p>{@code NetworkManager} is the primary entry point for network lifecycle operations. The singleton
 * delegates to {@link DockerClient} for all Docker daemon communication.
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
    private final DockerClient dockerClient;

    /**
     * Creates a network manager backed by the given Docker client.
     *
     * @param dockerClient the Docker client; must not be {@code null}
     */
    private NetworkManager(DockerClient dockerClient) {
        this.dockerClient = Objects.requireNonNull(dockerClient);
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
        var controller = ResourceController.instance();
        var session = controller.ensureReady();
        var labels = session.labelsForNewResource();
        String sessionPart = session.sessionId().substring(0, 8);
        String randomPart = UUID.randomUUID().toString().substring(0, 8);
        String name = "altcontainers-" + sessionPart + "-" + randomPart;
        String id = dockerClient.createNetwork(name, labels);
        return new Network(name, id);
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
        dockerClient.destroyNetwork(network.id());
    }
}
