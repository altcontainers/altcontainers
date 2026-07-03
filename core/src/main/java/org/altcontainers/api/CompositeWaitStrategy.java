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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Abstract base class for composite wait strategies that delegate to a list of
 * child strategies.
 *
 * <p>Subclasses define the combination semantics ({@code allOf} or {@code anyOf})
 * via {@link #check(Container)}. Log dispatch fans out to all log-observing
 * children.
 */
abstract class CompositeWaitStrategy implements WaitStrategy {

    final List<WaitStrategy> strategies;

    /**
     * Creates a composite strategy from the given child strategies.
     *
     * @param strategies the child strategies; must not be {@code null} or
     *     contain {@code null} elements
     * @throws NullPointerException if {@code strategies} is {@code null} or
     *     contains a {@code null} element
     */
    CompositeWaitStrategy(WaitStrategy... strategies) {
        Objects.requireNonNull(strategies, "strategies must not be null");
        for (WaitStrategy s : strategies) {
            Objects.requireNonNull(s, "s must not be null");
        }
        this.strategies = List.copyOf(Arrays.asList(strategies));
    }

    /**
     * Returns a fresh copy with fresh copies of all child strategies.
     *
     * @return a new composite strategy instance
     */
    @Override
    public WaitStrategy newAttemptCondition() {
        WaitStrategy[] fresh =
                strategies.stream().map(WaitStrategy::newAttemptCondition).toArray(WaitStrategy[]::new);
        return create(fresh);
    }

    /**
     * Returns a consumer that fans out each log line to all log-observing child
     * strategies, or {@code null} if none of the children observe logs.
     *
     * @return a composite log-line consumer, or {@code null}
     */
    @Override
    public Consumer<String> logLineConsumer() {
        List<Consumer<String>> consumers = strategies.stream()
                .map(WaitStrategy::logLineConsumer)
                .filter(Objects::nonNull)
                .toList();
        if (consumers.isEmpty()) {
            return null;
        }
        // Single consumer that fans out to all sub-consumers.
        // Each consumer processes the line independently.
        return line -> consumers.forEach(c -> c.accept(line));
    }

    /**
     * Returns a new composite of the same type with the given fresh child
     * strategies.
     *
     * @param strategies the fresh child strategies
     * @return a new composite strategy
     */
    abstract CompositeWaitStrategy create(WaitStrategy... strategies);
}
