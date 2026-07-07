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
import java.util.Objects;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.StartupCheckStrategy;

/**
 * Singleton startup check strategy that requires the container to still be
 * running after Docker reports it started.
 *
 * <p>This class resides in the {@code nonapi} package because direct
 * construction is unsupported. Use
 * {@link StartupCheckStrategy#isRunning()} to obtain the singleton instance.
 */
public final class IsRunningStartupCheckStrategy implements StartupCheckStrategy {

    /** The singleton instance. */
    public static final StartupCheckStrategy INSTANCE = new IsRunningStartupCheckStrategy();

    private IsRunningStartupCheckStrategy() {
        // Singleton.
    }

    @Override
    public void waitUntilStartupSuccessful(Container container, Duration timeout) {
        Objects.requireNonNull(container, "container must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (ContainerManager.getInstance().isContainerRunning(container.id())) {
            return;
        }
        throw new ContainerException("Container " + container.id() + " for image " + container.image()
                + " failed startup check: container failed to remain running within " + format(timeout));
    }

    private static String format(Duration timeout) {
        long timeoutMillis = timeout.toMillis();
        return (timeoutMillis >= 1000 && timeoutMillis % 1000 == 0) ? timeout.toSeconds() + "s" : timeoutMillis + "ms";
    }
}
