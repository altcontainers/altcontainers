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

package nonapi.org.altcontainers;

import static nonapi.org.altcontainers.ImageOperations.pullImageIfMissing;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ContainerManagerIntegrationTest {

    private static final String TEST_IMAGE = "alpine:latest";

    private static DockerClient client;

    private final List<String> containerIds = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        client = DockerClient.instance();
        pullImageIfMissing(client, TEST_IMAGE);
    }

    @AfterEach
    void tearDown() {
        for (String id : containerIds) {
            try {
                client.destroyContainer(id);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        containerIds.clear();
    }

    @Test
    void shouldRetryWhenStartupConsumerThrowsRuntimeException() {
        AtomicInteger callCount = new AtomicInteger(0);

        ContainerSpec spec = ContainerSpec.builder(TEST_IMAGE)
                .command("sleep", "infinity")
                .startupAttempts(2)
                .startupTimeout(Duration.ofSeconds(10))
                .startupConsumer(container -> {
                    if (callCount.incrementAndGet() == 1) {
                        throw new IllegalStateException("transient consumer failure");
                    }
                })
                .build();

        Container container = Container.create(spec);
        containerIds.add(container.id());

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(container.id()).isNotBlank();
    }
}
