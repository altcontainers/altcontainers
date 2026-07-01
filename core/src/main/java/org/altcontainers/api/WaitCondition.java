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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A readiness condition evaluated against a started container.
 *
 * <p>Each condition carries independent state and must be cloned via {@link #newAttemptCondition()} for
 * each startup attempt.
 */
public abstract sealed class WaitCondition
        permits WaitCondition.PortWait, WaitCondition.LogWait, WaitCondition.HttpWait {

    /**
     * Constructor for subclasses.
     */
    protected WaitCondition() {}

    /**
     * Returns a fresh copy of this condition for a new startup attempt, carrying the same configuration
     * but reset state.
     *
     * @return a new condition instance
     */
    public abstract WaitCondition newAttemptCondition();

    /**
     * Returns whether this condition is currently satisfied.
     *
     * @param container the container to evaluate against
     * @return {@code true} if the condition is satisfied
     */
    public abstract boolean check(Container container);

    /**
     * Readiness condition that probes the mapped host port for an open TCP connection. Stateless; each
     * call to {@link #newAttemptCondition()} returns the same instance.
     */
    public static final class PortWait extends WaitCondition {

        private static final String PORT_PROBE_HOST = "localhost";
        private static final int PORT_PROBE_TIMEOUT_MILLIS = 1000;

        private final int containerPort;

        /**
         * Creates a port-wait condition for the given container port.
         *
         * @param containerPort the container port whose mapped host port should be probed
         */
        public PortWait(int containerPort) {
            this.containerPort = containerPort;
        }

        @Override
        public WaitCondition newAttemptCondition() {
            return this;
        }

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
    }

    /**
     * Readiness condition that counts log lines matching a whole-string regex. Each call to
     * {@link #newAttemptCondition()} returns a new instance with the same pattern and required count but
     * a fresh counter, so stale log lines from prior attempts cannot satisfy a new attempt.
     */
    public static final class LogWait extends WaitCondition {

        private final Pattern pattern;
        private final int requiredCount;
        private final AtomicInteger count;

        /**
         * Creates a log-wait condition.
         *
         * @param regex the whole-string regular expression, compiled with {@link Pattern#DOTALL}
         * @param requiredCount the number of matching lines required
         */
        public LogWait(String regex, int requiredCount) {
            this.pattern = Pattern.compile(regex, Pattern.DOTALL);
            this.requiredCount = requiredCount;
            this.count = new AtomicInteger(0);
        }

        /**
         * Private constructor for cloning with the same pattern and count but a fresh counter.
         *
         * @param pattern the compiled pattern
         * @param requiredCount the number of matching lines required
         * @param count the counter instance
         */
        private LogWait(Pattern pattern, int requiredCount, AtomicInteger count) {
            this.pattern = pattern;
            this.requiredCount = requiredCount;
            this.count = count;
        }

        @Override
        public WaitCondition newAttemptCondition() {
            return new LogWait(pattern, requiredCount, new AtomicInteger(0));
        }

        /**
         * Increments the match count if the given raw line matches the pattern.
         *
         * @param line the raw (newline-terminated) log line
         */
        public void incrementIfMatches(String line) {
            if (pattern.matcher(line).matches()) {
                count.incrementAndGet();
            }
        }

        @Override
        public boolean check(Container container) {
            return count.get() >= requiredCount;
        }
    }

    /**
     * Readiness condition that issues an HTTP/HTTPS GET against the mapped host port and
     * considers the container ready when the response status falls within an inclusive
     * range (default {@value DEFAULT_MIN_STATUS}..{@value DEFAULT_MAX_STATUS}).
     *
     * <p>Unlike {@link PortWait}, which can be satisfied by Docker's userland proxy
     * binding the published host port before the in-container process is serving, an
     * HTTP response in range proves the service is actually handling requests.
     *
     * <p>Stateless: each {@link #check(Container)} issues a fresh request;
     * {@link #newAttemptCondition()} returns {@code this}, so the same instance is
     * reused across startup attempts.
     *
     * <p>The probed container port must be exposed and published (via
     * {@code ContainerSpec.Builder#exposePorts(int...)}) so that
     * {@link Container#hostPort(int)} resolves to a valid host port; otherwise
     * {@link #check(Container)} returns {@code false}.
     */
    public static final class HttpWait extends WaitCondition {

        /**
         * Default lower bound (inclusive) for an acceptable response status.
         */
        public static final int DEFAULT_MIN_STATUS = 200;

        /**
         * Default upper bound (inclusive) for an acceptable response status.
         */
        public static final int DEFAULT_MAX_STATUS = 399;

        private static final String PROBE_HOST = "localhost";
        private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 2_000;
        private static final int PORT_PROBE_TIMEOUT_MILLIS = 1_000;

        private final int containerPort;
        private final String path;
        private final Protocol protocol;
        private final int minStatus;
        private final int maxStatus;
        private final Duration requestTimeout;
        private final HttpClient httpClient;

        /**
         * Creates an HTTP-wait condition probing the given container port and path,
         * requiring a response status in the inclusive range {@code [minStatus, maxStatus]}.
         *
         * <p>The path is normalized to begin with {@code /} if it does not already.
         * Inputs are trusted; callers should validate via
         * {@code ContainerSpec.Builder#waitForHttpResponse(int, String)} or equivalent.
         *
         * @param containerPort the container port whose mapped host port is probed
         * @param path the request path; normalized to begin with {@code /}
         * @param protocol the HTTP protocol variant (HTTP or HTTPS)
         * @param minStatus inclusive lower bound for an acceptable status
         * @param maxStatus inclusive upper bound for an acceptable status
         */
        public HttpWait(int containerPort, String path, Protocol protocol, int minStatus, int maxStatus) {
            this.containerPort = containerPort;
            this.path = normalizePath(path);
            this.protocol = protocol;
            this.minStatus = minStatus;
            this.maxStatus = maxStatus;
            this.requestTimeout = Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS);
            this.httpClient =
                    HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        }

        /**
         * Returns {@code this}; this condition is stateless.
         *
         * @return this instance
         */
        @Override
        public WaitCondition newAttemptCondition() {
            return this;
        }

        /**
         * Returns {@code true} iff a GET to {@code <protocol>://localhost:<hostPort><path>}
         * returns a status in {@code [minStatus, maxStatus]}.
         *
         * <p>Returns {@code false} if the port is unmapped ({@code hostPort <= 0}), the
         * request fails or times out (any {@link IOException}), or the status is out of
         * range. On {@link InterruptedException} the interrupt status is restored and
         * {@code false} is returned, so the outer readiness loop propagates the interrupt.
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

            // Fast-fail: check if port is accepting connections before attempting HTTP/HTTPS
            // This mirrors PortWait behavior but is kept inline to avoid coupling.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(PROBE_HOST, hostPort), PORT_PROBE_TIMEOUT_MILLIS);
            } catch (IOException e) {
                // Port not accepting connections yet
                return false;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(protocol.scheme() + "://" + PROBE_HOST + ":" + hostPort + path))
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
    }
}
