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

/**
 * A readiness strategy evaluated against a started container.
 *
 * <p>Implementations must satisfy these contracts:
 *
 * <ul>
 *   <li><strong>Thread-safe:</strong> {@link #check(Container)} may be called
 *       from multiple threads concurrently.
 *   <li><strong>Idempotent:</strong> Repeated calls to {@code check} with the
 *       same container state must return the same result.
 *   <li><strong>Retry-safe:</strong> {@code check} must not mutate the
 *       container or start threads. Implementations may record diagnostic
 *       state from the last probe.
 * </ul>
 *
 * <p>Built-in strategies are available through the {@link Wait} factory class.
 * Custom strategies implement this interface directly as a lambda or method
 * reference:
 *
 * <pre>{@code
 * WaitStrategy fileExists = container -> {
 *     int port = container.hostPort(8080);
 *     // probe the container and return true/false
 * };
 * }</pre>
 *
 * @see Wait
 */
@FunctionalInterface
public interface WaitStrategy {

    /**
     * Returns whether this strategy is currently satisfied.
     *
     * @param container the container to evaluate against; never {@code null}
     * @return {@code true} if the strategy is satisfied
     */
    boolean check(Container container);
}
