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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import nonapi.org.altcontainers.api.AltcontainersProperties;
import nonapi.org.altcontainers.api.ManagedWaitStrategy;

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
public final class PortWaitStrategy implements ManagedWaitStrategy {

    private final int containerPort;
    private final int portProbeTimeoutMillis;
    private volatile String lastHost;
    private volatile Integer lastMappedPort;
    private volatile String lastError = "not probed yet";

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
        this.portProbeTimeoutMillis =
                (int) AltcontainersProperties.instance().portProbeTimeout().toMillis();
    }

    /**
     * Returns {@code this}; this strategy is stateless.
     *
     * @return this instance
     */
    @Override
    public ManagedWaitStrategy newAttemptCondition() {
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
        String host = container.host();
        Integer hostPort = container.hostPort(containerPort);
        lastHost = host;
        lastMappedPort = hostPort;
        if (hostPort == null) {
            lastError = "mapped host port unavailable";
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, hostPort), portProbeTimeoutMillis);
            lastError = null;
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    /**
     * Returns a timeout diagnostic including the target host and mapped port.
     *
     * @param container the container that did not become ready
     * @param startupTimeout the readiness timeout
     * @return a diagnostic message
     */
    @Override
    public String timeoutDiagnostic(Container container, Duration startupTimeout) {
        String host = lastHost != null ? lastHost : "unknown";
        String mapped = lastMappedPort != null ? String.valueOf(lastMappedPort) : "unavailable";
        String error = lastError != null ? "; last error: " + lastError : "";
        return "PortWaitStrategy failed for image " + container.image() + ", container " + container.id()
                + ", host " + host + ", container port " + containerPort + ", mapped host port " + mapped
                + " within " + format(startupTimeout) + error;
    }

    private static String format(Duration timeout) {
        long timeoutMillis = timeout.toMillis();
        return (timeoutMillis >= 1000 && timeoutMillis % 1000 == 0) ? timeout.toSeconds() + "s" : timeoutMillis + "ms";
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
