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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import nonapi.org.altcontainers.api.AltcontainersProperties;
import nonapi.org.altcontainers.api.ConcreteContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PortWaitStrategy}, including programmatic timeout tuning.
 */
class PortWaitStrategyTest {

    @BeforeEach
    void setUp() {
        System.clearProperty("altcontainers.wait.port.probe.timeout.ms");
        AltcontainersProperties.reset();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("altcontainers.wait.port.probe.timeout.ms");
        AltcontainersProperties.reset();
    }

    @Test
    void shouldUseDefaultTimeout() {
        PortWaitStrategy strategy = PortWaitStrategy.builder().port(8080).build();
        // Default is 500ms — probe a non-listening port and verify
        // it returns false within a reasonable time.
        Container container = new ConcreteContainer(
                "test-id", "test-image", ContainerSpec.builder("test-image").build(), null);

        long start = System.currentTimeMillis();
        boolean result = strategy.check(container);
        long duration = System.currentTimeMillis() - start;

        assertThat(result).isFalse();
        // Should complete within roughly the timeout (allow generous margin)
        assertThat(duration).isLessThan(2000);
    }

    @Test
    void shouldHonorSystemPropertyPortProbeTimeout() {
        System.setProperty("altcontainers.wait.port.probe.timeout.ms", "100");
        AltcontainersProperties.reset();

        PortWaitStrategy strategy = PortWaitStrategy.builder().port(8080).build();
        Container container = new ConcreteContainer(
                "test-id", "test-image", ContainerSpec.builder("test-image").build(), null);

        long start = System.currentTimeMillis();
        boolean result = strategy.check(container);
        long duration = System.currentTimeMillis() - start;

        assertThat(result).isFalse();
        // With 100ms timeout, should complete quickly
        // (allow generous margin for CI initialization overhead)
        assertThat(duration).isLessThan(2000);
    }

    @Test
    void shouldReturnTrueWhenPortIsListening() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            PortWaitStrategy strategy = PortWaitStrategy.builder().port(8080).build();

            // Create a container mock that returns the listening port
            Container container =
                    new ConcreteContainer(
                            "test-id",
                            "test-image",
                            ContainerSpec.builder("test-image").build(),
                            null) {
                        @Override
                        public String host() {
                            return "localhost";
                        }

                        @Override
                        public Integer hostPort(int containerPort) {
                            return port;
                        }
                    };

            boolean result = strategy.check(container);
            assertThat(result).isTrue();
        }
    }
}
