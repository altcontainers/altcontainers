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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * A readiness strategy that issues an HTTP or HTTPS GET against the mapped
 * host port and considers the container ready when the response status falls
 * within an inclusive range. Stateless; each call to {@link #check(Container)}
 * issues a fresh request.
 *
 * <p>Unlike {@link PortWaitStrategy}, which can be satisfied by Docker's
 * userland proxy binding the published host port before the in-container
 * process is serving, an HTTP response in range proves the service is
 * actually handling requests.
 *
 * <p>Created directly or via
 * {@link Wait#forHttpResponse(int, String, Protocol, int, int)}.
 *
 * <pre>{@code
 * // Direct construction
 * WaitStrategy http = new HttpWaitStrategy(8080, "/health", Protocol.HTTP, 200, 399);
 *
 * // Factory
 * WaitStrategy http = Wait.forHttpResponse(8080, "/health", Protocol.HTTP, 200, 399);
 * }</pre>
 */
public final class HttpWaitStrategy implements WaitStrategy {

    /**
     * Default lower bound (inclusive) for an acceptable response status.
     */
    public static final int DEFAULT_MIN_STATUS = 200;

    /**
     * Default upper bound (inclusive) for an acceptable response status.
     */
    public static final int DEFAULT_MAX_STATUS = 399;

    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 2_000;

    private final int containerPort;
    private final String path;
    private final Protocol protocol;
    private final int minStatus;
    private final int maxStatus;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    /**
     * Creates an HTTP-wait strategy probing the given container port and path,
     * requiring a response status in the inclusive range
     * {@code [minStatus, maxStatus]}.
     *
     * <p>The path is normalized to begin with {@code /} if it does not
     * already. Inputs are validated; callers can also validate via
     * {@code ContainerSpec.Builder#waitForHttpResponse(int, String)} or
     * equivalent.
     *
     * @param containerPort the container port whose mapped host port is
     *     probed; must be in {@code 1..65535}
     * @param path the request path; normalized to begin with {@code /};
     *     must not be {@code null} or blank
     * @param protocol the HTTP protocol variant (HTTP or HTTPS)
     * @param minStatus inclusive lower bound for an acceptable status;
     *     must be in {@code 100..599}
     * @param maxStatus inclusive upper bound for an acceptable status;
     *     must be in {@code 100..599}, and {@code >= minStatus}
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public HttpWaitStrategy(int containerPort, String path, Protocol protocol, int minStatus, int maxStatus) {
        if (containerPort < 1 || containerPort > 65535) {
            throw new IllegalArgumentException("containerPort must be in 1..65535, was " + containerPort);
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (minStatus < 100 || minStatus > 599) {
            throw new IllegalArgumentException("minStatus must be in 100..599, was " + minStatus);
        }
        if (maxStatus < 100 || maxStatus > 599) {
            throw new IllegalArgumentException("maxStatus must be in 100..599, was " + maxStatus);
        }
        if (minStatus > maxStatus) {
            throw new IllegalArgumentException("minStatus must be <= maxStatus, was " + minStatus + " > " + maxStatus);
        }
        this.containerPort = containerPort;
        this.path = normalizePath(path);
        Objects.requireNonNull(protocol, "protocol must not be null");
        this.protocol = protocol;
        this.minStatus = minStatus;
        this.maxStatus = maxStatus;
        this.requestTimeout = Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS);
        this.httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
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
     * Returns {@code true} iff a GET to
     * {@code <protocol>://localhost:<hostPort><path>} returns a status in
     * {@code [minStatus, maxStatus]}.
     *
     * <p>Returns {@code false} if the port is unmapped ({@code hostPort <= 0}),
     * the request fails or times out (any {@link IOException}), or the status
     * is out of range. On {@link InterruptedException} the interrupt status is
     * restored and {@code false} is returned, so the outer readiness loop
     * propagates the interrupt.
     *
     * @param container the container to evaluate against
     * @return {@code true} if an acceptable HTTP/HTTPS response was received
     */
    @Override
    public boolean check(Container container) {
        int hostPort = container.hostPort(containerPort);
        if (hostPort <= 0) {
            return false;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(protocol.scheme() + "://localhost:" + hostPort + path))
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= minStatus && status <= maxStatus;
        } catch (IOException e) {
            // Connect refused, connection reset, or read timeout: not ready — retry on next poll.
            return false;
        } catch (InterruptedException e) {
            // Restore the interrupt status so the outer readiness loop observes it via its
            // Thread.sleep and propagates as ContainerException. Never swallow an interrupt.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Returns a new builder for configuring an HTTP-wait strategy.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for configuring a {@link HttpWaitStrategy}.
     *
     * <p>Defaults: protocol {@link Protocol#HTTP}, status range
     * {@value #DEFAULT_MIN_STATUS}..{@value #DEFAULT_MAX_STATUS}.
     *
     * <p>Instances are mutable and not thread-safe.
     */
    public static final class Builder {

        private int port = -1;
        private String path;
        private Protocol protocol = Protocol.HTTP;
        private int minStatus = DEFAULT_MIN_STATUS;
        private int maxStatus = DEFAULT_MAX_STATUS;

        /**
         * Creates a new builder. Use {@link HttpWaitStrategy#builder()}
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
         * Sets the request path, normalized to begin with {@code /}.
         *
         * @param path the request path; must not be {@code null} or blank
         * @return this builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Sets the protocol. Default is {@link Protocol#HTTP}.
         *
         * @param protocol the protocol; must not be {@code null}
         * @return this builder
         */
        public Builder protocol(Protocol protocol) {
            this.protocol = Objects.requireNonNull(protocol, "protocol");
            return this;
        }

        /**
         * Sets the acceptable response status range (inclusive).
         * Default is {@value HttpWaitStrategy#DEFAULT_MIN_STATUS}..
         * {@value HttpWaitStrategy#DEFAULT_MAX_STATUS}.
         *
         * @param minStatus inclusive lower bound; must be in
         *     {@code 100..599}
         * @param maxStatus inclusive upper bound; must be in
         *     {@code 100..599} and {@code >= minStatus}
         * @return this builder
         */
        public Builder statusRange(int minStatus, int maxStatus) {
            this.minStatus = minStatus;
            this.maxStatus = maxStatus;
            return this;
        }

        /**
         * Builds an immutable {@link HttpWaitStrategy}.
         *
         * @return a new HTTP-wait strategy
         * @throws IllegalArgumentException if {@code port} or
         *     {@code path} is invalid, or if the status range is invalid
         */
        public HttpWaitStrategy build() {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be set in 1..65535, was " + port);
            }
            return new HttpWaitStrategy(port, path, protocol, minStatus, maxStatus);
        }
    }
}
