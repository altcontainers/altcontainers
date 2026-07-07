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

import java.util.regex.Pattern;

/**
 * Convenience factory and composition methods for {@link WaitStrategy}
 * instances.
 *
 * <p>These factories are shortcuts for builder calls. Both paths
 * are equivalent and fully supported:
 *
 * <pre>{@code
 * // Builder
 * WaitStrategy port = PortWaitStrategy.builder().port(8080).build();
 * WaitStrategy log = LogWaitStrategy.builder().pattern(".*started.*").build();
 *
 * // Factory — same result, more discoverable
 * WaitStrategy port = Wait.forListeningPort(8080);
 * WaitStrategy log = Wait.forLogMessage(".*started.*", 1);
 * }</pre>
 *
 * <p>Composition is only available through factories — the composite
 * strategy classes are internal implementation details.
 *
 * <p>The builder convenience methods on {@link GenericContainerSpec.Builder}
 * delegate to these factories.
 */
public final class Wait {

    private Wait() {
        // Intentionally empty
    }

    /**
     * Creates a strategy that probes the mapped host port for an open TCP
     * connection. Convenience wrapper for
     * {@code PortWaitStrategy.builder().port(containerPort).build()}. Stateless.
     *
     * @param containerPort the container port whose mapped host port is
     *     probed; must be in {@code 1..65535}
     * @return a port-probe wait strategy
     */
    public static WaitStrategy forListeningPort(int containerPort) {
        return PortWaitStrategy.builder().port(containerPort).build();
    }

    /**
     * Creates a strategy that counts log lines matching the given
     * regex (with {@link Pattern#DOTALL}) applied against raw (newline-terminated)
     * log line content.
     * Convenience wrapper for {@code LogWaitStrategy.builder().pattern(regex).times(times).build()}.
     * Stateful; each startup attempt creates a fresh counter.
     *
     * @param regex the whole-string regular expression
     * @param times the number of matching lines required; must be {@code >= 1}
     * @return a log-message wait strategy
     */
    public static WaitStrategy forLogMessage(String regex, int times) {
        return LogWaitStrategy.builder().pattern(regex).times(times).build();
    }

    /**
     * Creates a strategy that issues an HTTP or HTTPS GET against the mapped
     * host port and checks the response status. Convenience wrapper for
     * {@code HttpWaitStrategy.builder().port(containerPort).path(path).protocol(protocol).statusRange(minStatus, maxStatus).build()}.
     * Stateless.
     *
     * @param protocol the protocol; see {@link HttpWaitStrategy.Protocol}
     * @param containerPort the container port whose mapped host port is
     *     probed; must be in {@code 1..65535}
     * @param path the request path; normalized to begin with {@code /}
     * @param minStatus inclusive lower bound for acceptable status
     * @param maxStatus inclusive upper bound for acceptable status
     * @return an HTTP-response wait strategy
     */
    public static WaitStrategy forHttpResponse(
            HttpWaitStrategy.Protocol protocol, int containerPort, String path, int minStatus, int maxStatus) {
        return HttpWaitStrategy.builder()
                .port(containerPort)
                .path(path)
                .protocol(protocol)
                .statusRange(minStatus, maxStatus)
                .build();
    }

    /**
     * Creates a strategy that issues an HTTP or HTTPS GET against the mapped
     * host port at path {@code /} and checks the response status.
     * Convenience wrapper for
     * {@code HttpWaitStrategy.builder().port(containerPort).path("/").protocol(protocol).statusRange(minStatus, maxStatus).build()}.
     * Stateless.
     *
     * @param protocol the protocol; see {@link HttpWaitStrategy.Protocol}
     * @param containerPort the container port whose mapped host port is
     *     probed; must be in {@code 1..65535}
     * @param minStatus inclusive lower bound for acceptable status
     * @param maxStatus inclusive upper bound for acceptable status
     * @return an HTTP-response wait strategy
     */
    public static WaitStrategy forHttpResponse(
            HttpWaitStrategy.Protocol protocol, int containerPort, int minStatus, int maxStatus) {
        return forHttpResponse(protocol, containerPort, "/", minStatus, maxStatus);
    }

    /**
     * Returns a strategy that is satisfied when all of the given strategies
     * are satisfied. Log lines are dispatched to all log-observing
     * sub-strategies.
     *
     * @param strategies the sub-strategies; must not be {@code null} or
     *     contain {@code null} elements
     * @return a composite all-of strategy
     */
    public static WaitStrategy allOf(WaitStrategy... strategies) {
        return new AllOfWaitStrategy(strategies);
    }

    /**
     * Returns a strategy that is satisfied when any of the given strategies
     * is satisfied. Log lines are dispatched to all log-observing
     * sub-strategies.
     *
     * @param strategies the sub-strategies; must not be {@code null} or
     *     contain {@code null} elements
     * @return a composite any-of strategy
     */
    public static WaitStrategy anyOf(WaitStrategy... strategies) {
        return new AnyOfWaitStrategy(strategies);
    }
}
