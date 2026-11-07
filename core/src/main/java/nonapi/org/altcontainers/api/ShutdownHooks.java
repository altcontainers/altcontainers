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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Registers the JVM shutdown hook that drains a stop executor.
 *
 * <p>This class resides in the {@code nonapi} package because it is an
 * implementation detail shared by the container and network managers.
 */
public final class ShutdownHooks {

    private static final long TERMINATION_TIMEOUT_SECONDS = 10L;

    private ShutdownHooks() {
        // Intentionally empty
    }

    /**
     * Registers a shutdown hook that marks shutdown in progress, then drains the
     * executor, forcing it down if it does not terminate in time.
     *
     * <p>The executor is supplied rather than passed, because the managers
     * register their hook from a constructor that runs while their static
     * fields are still being initialized; the field holds its value only once
     * the hook runs.
     *
     * @param threadName the shutdown hook thread name
     * @param markShuttingDown action run first to flag shutdown in progress
     * @param stopExecutor supplies the executor to drain, resolved when the hook runs
     */
    public static void registerStopExecutorShutdown(
            String threadName, Runnable markShuttingDown, Supplier<ExecutorService> stopExecutor) {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(stopExecutorShutdown(markShuttingDown, stopExecutor), threadName));
    }

    /**
     * Builds the shutdown task: mark shutdown in progress, drain the executor,
     * and force it down if it does not terminate in time.
     *
     * @param markShuttingDown action run first to flag shutdown in progress
     * @param stopExecutor supplies the executor to drain, resolved when the task runs
     * @return the shutdown task, package-private for testing
     */
    static Runnable stopExecutorShutdown(Runnable markShuttingDown, Supplier<ExecutorService> stopExecutor) {
        return () -> {
            markShuttingDown.run();
            ExecutorService executor = stopExecutor.get();
            executor.shutdown();
            try {
                executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        };
    }
}
