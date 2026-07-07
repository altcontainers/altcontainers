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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import nonapi.org.altcontainers.api.ManagedWaitStrategy;

/**
 * Abstract base class for composite wait strategies that delegate to a list of
 * child strategies.
 *
 * <p>Subclasses define the combination semantics ({@code allOf} or {@code anyOf})
 * via {@link #check(Container)}. Log dispatch fans out to all log-observing
 * children.
 */
abstract class CompositeWaitStrategy implements ManagedWaitStrategy {

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
     * Returns a fresh copy with fresh copies of all managed child strategies.
     * Lambda children (not implementing {@link ManagedWaitStrategy}) are reused
     * as-is.
     *
     * @return a new composite strategy instance
     */
    @Override
    public ManagedWaitStrategy newAttemptCondition() {
        WaitStrategy[] fresh = strategies.stream()
                .map(s -> s instanceof ManagedWaitStrategy m ? m.newAttemptCondition() : s)
                .toArray(WaitStrategy[]::new);
        return create(fresh);
    }

    /**
     * Returns a consumer that fans out each log line to all managed
     * log-observing child strategies, or {@code null} if none of the
     * managed children observe logs.
     *
     * @return a composite log-line consumer, or {@code null}
     */
    @Override
    public Consumer<String> logLineConsumer() {
        List<Consumer<String>> consumers = strategies.stream()
                .filter(s -> s instanceof ManagedWaitStrategy)
                .map(s -> ((ManagedWaitStrategy) s).logLineConsumer())
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
     * Returns a timeout diagnostic joining diagnostics from all managed children,
     * falling back to the simple class name for non-managed children.
     *
     * @param container the container that did not become ready
     * @param startupTimeout the startup/readiness timeout
     * @return a diagnostic message
     */
    @Override
    public String timeoutDiagnostic(Container container, Duration startupTimeout) {
        return String.join(
                "; ",
                strategies.stream()
                        .map(s -> s instanceof ManagedWaitStrategy m
                                ? m.timeoutDiagnostic(container, startupTimeout)
                                : s.getClass().getSimpleName())
                        .toList());
    }

    /**
     * Returns a new composite of the same type with the given fresh child
     * strategies.
     *
     * @param strategies the fresh child strategies
     * @return a new composite strategy
     */
    abstract ManagedWaitStrategy create(WaitStrategy... strategies);
}
