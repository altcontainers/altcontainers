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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.altcontainers.api.Altcontainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Docker host resolution exposed through the container manager.
 *
 * <p>Uses programmatic {@link Altcontainers#configure(Consumer)} because
 * {@code AltcontainersProperties} is an eagerly-initialized singleton. System
 * properties set after first access have no effect. System property resolution
 * is tested separately in {@code AltcontainersPropertiesTest}.
 */
class ContainerManagerHostTest {

    @AfterEach
    void tearDown() {
        Altcontainers.configure(null);
    }

    @Test
    void shouldReturnDockerHostFromTcpConfiguration() {
        Altcontainers.configure(c -> c.dockerHost("tcp://192.0.2.10:2375"));

        assertThat(ContainerManager.getInstance().host()).isEqualTo("192.0.2.10");
    }

    @Test
    void shouldReturnLocalhostForUnixDockerHost() {
        Altcontainers.configure(c -> c.dockerHost("unix:///var/run/docker.sock"));

        assertThat(ContainerManager.getInstance().host()).isEqualTo("localhost");
    }
}
