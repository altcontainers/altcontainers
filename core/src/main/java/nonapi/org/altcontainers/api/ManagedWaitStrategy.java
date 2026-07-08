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
import java.util.function.Consumer;
import org.altcontainers.api.Container;
import org.altcontainers.api.WaitStrategy;

/**
 * Internal framework contract for built-in wait strategies.
 *
 * <p>This type is public only so public strategies in {@code org.altcontainers.api}
 * can implement it from a different package. User code should implement
 * {@link WaitStrategy} instead.
 */
public interface ManagedWaitStrategy extends WaitStrategy {

    /**
     * Returns a fresh copy of this strategy for a new startup attempt,
     * carrying the same configuration but reset state.
     *
     * <p>The default implementation returns {@code this}, which is correct
     * for stateless strategies. Stateful strategies (such as log-matching
     * counters) override this to return a new instance with a fresh counter.
     *
     * @return a new attempt strategy, or {@code this}
     */
    default ManagedWaitStrategy newAttemptCondition() {
        return this;
    }

    /**
     * Returns a consumer for UTF-8-decoded container log lines, or {@code null} if this strategy does
     * not observe container logs.
     *
     * <p>The default implementation returns {@code null}. Log-based strategies override this to receive
     * UTF-8-decoded text from Docker frames, used internally for text-oriented wait strategies. This is
     * an internal contract, not the public output callback. The consumer is invoked from a single callback
     * thread and does not need to be thread-safe with respect to other log consumers.
     *
     * @return a log-line consumer, or {@code null}
     */
    default Consumer<String> logLineConsumer() {
        return null;
    }

    /**
     * Returns a human-readable timeout diagnostic for this strategy.
     *
     * <p>Implementations may include the configured target, last observed probe
     * error/status, and the relevant timeout. The default is the simple class
     * name.
     *
     * @param container the container that did not become ready
     * @param startupTimeout the startup/readiness timeout
     * @return a timeout diagnostic
     */
    default String timeoutDiagnostic(Container container, Duration startupTimeout) {
        return getClass().getSimpleName();
    }
}
