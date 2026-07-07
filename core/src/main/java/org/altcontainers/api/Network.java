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

import nonapi.org.altcontainers.api.NetworkManager;

/**
 * A runtime handle to a Docker network.
 *
 * <p>Networks are created via {@link #create()} and destroyed via
 * {@link #close()} or {@link #close(Network)}. The concrete
 * implementation lives in the {@code nonapi} package and is not
 * intended for direct construction.
 */
public interface Network extends AutoCloseable {

    /**
     * Creates a new Docker network.
     *
     * @return a network handle
     * @throws ContainerException if creation fails
     */
    static Network create() {
        return NetworkManager.getInstance().createNetwork();
    }

    /**
     * Destroys a network. Null-safe.
     *
     * @param network the network to close
     */
    static void close(Network network) {
        NetworkManager.getInstance().closeNetwork(network);
    }

    /**
     * Returns the network name.
     *
     * @return the name
     */
    String name();

    /**
     * Returns the network id.
     *
     * @return the id
     */
    String id();

    /**
     * Destroys this network.
     */
    @Override
    void close();
}
