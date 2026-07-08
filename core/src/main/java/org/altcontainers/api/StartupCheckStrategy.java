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
import nonapi.org.altcontainers.api.IsRunningStartupCheckStrategy;

/**
 * A startup-check strategy evaluated after Docker starts a container and before
 * readiness wait strategies run.
 *
 * <p>Startup checks answer whether the container process reached an acceptable
 * lifecycle state. Readiness wait strategies answer whether the service inside
 * the container is ready to use.
 */
@FunctionalInterface
public interface StartupCheckStrategy {

    /**
     * Waits until the container satisfies this startup-check strategy.
     *
     * @param container the started container handle
     * @param timeout the startup timeout configured for the container
     * @throws ContainerException if the container does not satisfy this startup
     *     check
     */
    void waitUntilStartupSuccessful(Container container, Duration timeout);

    /**
     * Returns the default startup check, which requires the container to be
     * running after Docker reports the container started.
     *
     * @return the default is-running startup check
     */
    static StartupCheckStrategy isRunning() {
        return IsRunningStartupCheckStrategy.INSTANCE;
    }
}
