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

import java.util.Objects;
import nonapi.org.altcontainers.NetworkManager;

/**
 * A runtime handle to an ephemeral Docker bridged network used to isolate integration-test containers.
 *
 * <p>Networks are created via {@link #create()} and must be released via
 * {@link #destroy()} or {@link #close()} once the associated containers have stopped. Containers join
 * the network through {@link ContainerSpec.Builder#network(Network, String...)}, using {@link #name()}
 * as the network mode, so that peers on the same network can resolve each other by alias.
 *
 * <p>{@code Network} is a thin, immutable facade: it holds only the network name and identifier and
 * delegates every Docker operation to the shared {@code DockerClient}. Instances are immutable and safe
 * to share between threads. It implements {@link AutoCloseable} and is intended for use in a
 * try-with-resources block so cleanup always runs, even on test failure:
 *
 * <pre>{@code
 * try (Network network = Network.create()) {
 *     ContainerSpec containerSpec = ContainerSpec.builder(image)
 *             .network(network, "exporter")
 *             .build();
 *     try (Container container = Container.create(spec)) {
 *         // ... assertions ...
 *     }
 * }
 * }</pre>
 *
 * <p>Destruction is idempotent and blocks until Docker confirms the network is gone, retrying transient
 * {@code endpoint still attached} failures; a failure that persists beyond the destroy deadline is
 * reported via {@link ContainerException}.
 */
public final class Network implements AutoCloseable {

    /**
     * The human-readable network name (a random UUID), passed to containers as the network mode.
     */
    private final String name;

    /**
     * The Docker-assigned network identifier used for removal and inspection.
     */
    private final String id;

    /**
     * Creates a network handle binding a name to its Docker identifier.
     *
     * @param name the Docker network name; must not be {@code null}
     * @param id the Docker network identifier; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Network(String name, String id) {
        this.name = Objects.requireNonNull(name);
        this.id = Objects.requireNonNull(id);
    }

    /**
     * Creates a new Docker bridged network with a session-scoped name.
     *
     * @return a runtime handle to the created network
     * @throws ContainerException if Docker fails to create the network or the reaper is unavailable
     */
    public static Network create() {
        return NetworkManager.getInstance().createNetwork();
    }

    /**
     * Destroys the given Docker network, blocking until Docker confirms it is gone. Null-safe.
     *
     * @param network the network to destroy, or {@code null} for a no-op
     * @throws ContainerException if the network is not confirmed destroyed within the destroy deadline,
     *     or if the calling thread is interrupted while waiting
     */
    public static void destroy(Network network) {
        NetworkManager.getInstance().destroyNetwork(network);
    }

    /**
     * Returns the Docker network name that containers join. Package-private: used by
     * {@link ContainerSpec.Builder#network(Network, String...)} as the network mode.
     *
     * @return the network name
     */
    String name() {
        return name;
    }

    /**
     * Returns the Docker-assigned network identifier used for removal and inspection.
     *
     * @return the network identifier
     */
    public String id() {
        return id;
    }

    /**
     * Destroy this Docker network, blocking until Docker confirms it is gone. Idempotent.
     *
     * <p>Issues an idempotent remove and then polls until the network can no longer be inspected, retrying
     * transient {@code endpoint still attached} failures. Returns only once destruction is confirmed.
     *
     * @throws ContainerException if the network is not confirmed destroyed within the destroy deadline,
     *     or if the calling thread is interrupted while waiting
     */
    public void destroy() {
        NetworkManager.getInstance().destroyNetwork(this);
    }

    /**
     * Destroys this network. Delegates to {@link #destroy()}. Implements {@link AutoCloseable} for use
     * in try-with-resources blocks.
     */
    @Override
    public void close() {
        destroy();
    }
}
