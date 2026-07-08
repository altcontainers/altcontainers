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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for Docker host resolution exposed through the container manager. */
class ContainerManagerHostTest {

    private static final String DOCKER_HOST_PROPERTY = "altcontainers.docker.host";

    @BeforeEach
    void setUp() {
        System.clearProperty(DOCKER_HOST_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(DOCKER_HOST_PROPERTY);
    }

    @Test
    void shouldReturnDockerHostFromTcpConfiguration() {
        System.setProperty(DOCKER_HOST_PROPERTY, "tcp://192.0.2.10:2375");

        assertThat(ContainerManager.getInstance().host()).isEqualTo("192.0.2.10");
    }

    @Test
    void shouldReturnLocalhostForUnixDockerHost() {
        System.setProperty(DOCKER_HOST_PROPERTY, "unix:///var/run/docker.sock");

        assertThat(ContainerManager.getInstance().host()).isEqualTo("localhost");
    }
}
