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

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A readiness strategy that probes the mapped host port for an open TCP
 * connection. Stateless; the same instance can be reused across startup
 * attempts.
 *
 * <p>Created via {@link #builder()} or {@link Wait#forListeningPort(int)}.
 *
 * <pre>{@code
 * // Builder
 * WaitStrategy port = PortWaitStrategy.builder().port(8080).build();
 *
 * // Factory
 * WaitStrategy port = Wait.forListeningPort(8080);
 * }</pre>
 */
public final class PortWaitStrategy implements WaitStrategy {

    private static final String PORT_PROBE_HOST = "localhost";
    private static final int PORT_PROBE_TIMEOUT_MILLIS = 1000;

    private final int containerPort;

    /**
     * Creates a port-wait strategy for the given container port.
     *
     * @param containerPort the container port whose mapped host port is
     *     probed; must be in {@code 1..65535}
     * @throws IllegalArgumentException if {@code containerPort} is outside
     *     {@code 1..65535}
     */
    public PortWaitStrategy(int containerPort) {
        if (containerPort < 1 || containerPort > 65535) {
            throw new IllegalArgumentException("containerPort must be in 1..65535, was " + containerPort);
        }
        this.containerPort = containerPort;
    }

    /**
     * Returns {@code this}; this strategy is stateless.
     *
     * @return this instance
     */
    @Override
    public WaitStrategy newAttemptCondition() {
        return this;
    }

    /**
     * Returns a new builder for configuring a port-wait strategy.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Probes the mapped host port for an open TCP connection.
     *
     * @param container the container to evaluate against
     * @return {@code true} if the mapped host port accepts a connection
     */
    @Override
    public boolean check(Container container) {
        int hostPort = container.hostPort(containerPort);
        if (hostPort <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(PORT_PROBE_HOST, hostPort), PORT_PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Mutable builder for configuring a {@link PortWaitStrategy}.
     *
     * <p>Instances are mutable and not thread-safe.
     */
    public static final class Builder {

        private int port = -1;

        /**
         * Creates a new builder. Use {@link PortWaitStrategy#builder()}
         * instead of calling this constructor directly.
         */
        private Builder() {
            // Intentionally empty
        }

        /**
         * Sets the container port to probe.
         *
         * @param port the container port; must be in {@code 1..65535}
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Builds an immutable {@link PortWaitStrategy}.
         *
         * @return a new port-wait strategy
         * @throws IllegalArgumentException if {@code port} is not in
         *     {@code 1..65535}
         */
        public PortWaitStrategy build() {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be set in 1..65535, was " + port);
            }
            return new PortWaitStrategy(port);
        }
    }
}
