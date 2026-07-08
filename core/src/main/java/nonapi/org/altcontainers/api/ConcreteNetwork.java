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

import java.util.Objects;
import org.altcontainers.api.Network;

/**
 * Concrete implementation of {@link Network} backed by a real Docker network.
 *
 * <p>This class resides in the {@code nonapi} package because direct construction
 * is unsupported. Use {@link Network#create()} to provision a network through
 * the public API.
 *
 * @param name the network name
 * @param id the network id
 */
public record ConcreteNetwork(String name, String id) implements Network {

    /**
     * Creates a concrete network handle.
     *
     * @param name the network name
     * @param id the network id
     */
    public ConcreteNetwork {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    /**
     * Destroys this network via the public API.
     */
    @Override
    public void close() {
        Network.close(this);
    }
}
