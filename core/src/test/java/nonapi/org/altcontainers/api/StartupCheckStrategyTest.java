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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.StartupCheckStrategy;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link nonapi.org.altcontainers.api.IsRunningStartupCheckStrategy} queries the daemon
 * for live container state rather than relying on cached post-start metadata.
 */
class StartupCheckStrategyTest {

    @Test
    void shouldFailWhenDaemonReportsContainerNotRunning() {
        // Create a Container with metadata that says running=true.
        // Without a daemon connection, ContainerManager.isContainerRunning()
        // returns false (daemon port is -1). The strategy should query live
        // state and throw ContainerException, proving it doesn't rely on
        // cached metadata.
        Container container = new ConcreteContainer(
                "test-id",
                "test-image:latest",
                ContainerSpec.builder("test-image:latest").build(),
                new ContainerMetadata("localhost", true, Map.of()));

        StartupCheckStrategy strategy = StartupCheckStrategy.isRunning();

        assertThatThrownBy(() -> strategy.waitUntilStartupSuccessful(container, Duration.ofSeconds(1)))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("failed startup check");
    }
}
