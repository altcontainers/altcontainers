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

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import nonapi.org.altcontainers.ContainerManager;
import nonapi.org.altcontainers.NetworkManager;
import org.junit.jupiter.api.Test;

class ContainerManagerValidationTest {

    @Test
    void shouldRejectNullSpec() {
        assertThatNullPointerException()
                .isThrownBy(() -> ContainerManager.getInstance().createContainer(null));
    }

    @Test
    void shouldRejectZeroStartupAttempts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").startupAttempts(0))
                .withMessageContaining("startupAttempts must be >= 1");
    }

    @Test
    void shouldRejectNegativeStartupAttempts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").startupAttempts(-1))
                .withMessageContaining("startupAttempts must be >= 1");
    }

    @Test
    void shouldRejectNullStartupTimeout() {
        // Builder rejects null immediately with NullPointerException.
        assertThatNullPointerException()
                .isThrownBy(() -> ContainerSpec.builder("img").startupTimeout(null))
                .withMessageContaining("startupTimeout must not be null");
    }

    @Test
    void shouldRejectZeroStartupTimeout() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").startupTimeout(Duration.ZERO).build();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerManager.getInstance().createContainer(containerSpec))
                .withMessageContaining("startupTimeout must be positive");
    }

    @Test
    void shouldRejectNegativeStartupTimeout() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .startupTimeout(Duration.ofSeconds(-1))
                .build();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerManager.getInstance().createContainer(containerSpec))
                .withMessageContaining("startupTimeout must be positive");
    }

    @Test
    void shouldAcceptNullContainerInDestroy() {
        // Null container is a no-op.
        ContainerManager.getInstance().destroyContainer(null);
    }

    @Test
    void shouldAcceptNullNetworkInDestroy() {
        // Null network is a no-op.
        NetworkManager.getInstance().destroyNetwork(null);
    }
}
