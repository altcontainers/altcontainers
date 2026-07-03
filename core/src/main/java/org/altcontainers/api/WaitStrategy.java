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

import java.util.function.Consumer;

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
 *   <li><strong>Side-effect-free:</strong> {@code check} must not mutate the
 *       container, start threads, or perform I/O that cannot be safely
 *       retried.
 * </ul>
 *
 * <p>Built-in strategies are available through the {@link Wait} factory class.
 * Custom strategies implement this interface directly:
 *
 * <pre>{@code
 * WaitStrategy fileExists = container -> {
 *     int port = container.hostPort(8080);
 *     // probe the container and return true/false
 * };
 * }</pre>
 *
 * <p>Strategies that observe container logs override {@link #logLineConsumer()}
 * to receive raw log lines during the readiness poll.
 *
 * @see Wait
 */
public interface WaitStrategy {

    /**
     * Returns whether this strategy is currently satisfied.
     *
     * @param container the container to evaluate against; never {@code null}
     * @return {@code true} if the strategy is satisfied
     */
    boolean check(Container container);

    /**
     * Returns a fresh copy of this strategy for a new startup attempt,
     * carrying the same configuration but reset state.
     *
     * <p>The default implementation returns {@code this}, which is correct
     * for stateless strategies. Stateful strategies (such as log-matching
     * counters) override this to return a new instance with a fresh counter.
     *
     * @return a new strategy instance, or {@code this} for stateless strategies
     */
    default WaitStrategy newAttemptCondition() {
        return this;
    }

    /**
     * Returns a consumer for raw, newline-terminated container log lines,
     * or {@code null} if this strategy does not observe container logs.
     *
     * <p>The default implementation returns {@code null}. Log-based strategies
     * override this to receive every raw log line emitted by the container
     * after startup, allowing them to update internal counters for pattern
     * matching. The consumer is invoked from a single callback thread and
     * does not need to be thread-safe with respect to other log consumers.
     *
     * @return a log-line consumer, or {@code null}
     */
    default Consumer<String> logLineConsumer() {
        return null;
    }
}
