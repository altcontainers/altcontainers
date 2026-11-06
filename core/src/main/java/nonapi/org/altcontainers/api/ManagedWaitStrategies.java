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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.altcontainers.api.Container;
import org.altcontainers.api.WaitStrategy;

/**
 * Helpers for treating a set of wait strategies uniformly, whether the set
 * comes from a container spec or from a composite strategy.
 *
 * <p>This class resides in the {@code nonapi} package because it is an
 * implementation detail shared by the public strategies and the container
 * manager.
 */
public final class ManagedWaitStrategies {

    private ManagedWaitStrategies() {
        // Intentionally empty
    }

    /**
     * Builds a consumer that fans out each log line to every log-observing
     * managed strategy in the list.
     *
     * @param strategies the strategies to observe
     * @return a composite log consumer, or {@code null} if none observe logs
     */
    public static Consumer<String> logLineConsumer(List<WaitStrategy> strategies) {
        List<Consumer<String>> consumers = strategies.stream()
                .filter(strategy -> strategy instanceof ManagedWaitStrategy)
                .map(strategy -> ((ManagedWaitStrategy) strategy).logLineConsumer())
                .filter(Objects::nonNull)
                .toList();
        if (consumers.isEmpty()) {
            return null;
        }
        return line -> consumers.forEach(consumer -> consumer.accept(line));
    }

    /**
     * Joins per-strategy timeout diagnostics with {@code "; "}, falling back to
     * the simple class name for strategies that are not managed.
     *
     * @param strategies the strategies that did not become ready
     * @param container the container that did not become ready
     * @param startupTimeout the startup/readiness timeout
     * @return the joined diagnostics
     */
    public static String joinDiagnostics(List<WaitStrategy> strategies, Container container, Duration startupTimeout) {
        return String.join(
                "; ",
                strategies.stream()
                        .map(strategy -> strategy instanceof ManagedWaitStrategy managed
                                ? managed.timeoutDiagnostic(container, startupTimeout)
                                : strategy.getClass().getSimpleName())
                        .toList());
    }
}
