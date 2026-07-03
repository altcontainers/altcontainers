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

import static nonapi.org.altcontainers.ContainerOperations.*;
import static nonapi.org.altcontainers.ImageOperations.*;
import static nonapi.org.altcontainers.NetworkOperations.*;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DockerClientNetworkReadyTest {

    private static final String TEST_IMAGE = "alpine:latest";
    private static final int ITERATIONS = 10;

    private static DockerClient client;

    private final List<String> containerIds = new ArrayList<>();
    private final List<String> networkIds = new ArrayList<>();

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
        for (String id : networkIds) {
            try {
                client.destroyNetwork(id);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        containerIds.clear();
        networkIds.clear();
    }

    @Test
    void shouldCreateNetworkAndStartContainerImmediately() {
        Map<String, String> labels = Map.of(
                "altcontainers-containers.managed",
                "true",
                "altcontainers-containers.session-id",
                UUID.randomUUID().toString(),
                "altcontainers-containers.created-at-ms",
                String.valueOf(System.currentTimeMillis()));

        for (int i = 0; i < ITERATIONS; i++) {
            String networkName = "altcontainers-test-" + UUID.randomUUID();
            String networkId = createNetwork(client, networkName, labels);
            networkIds.add(networkId);

            ContainerCreateSpec spec = new ContainerCreateSpec(
                    TEST_IMAGE,
                    List.of("sleep", "infinity"),
                    List.of(),
                    List.of(),
                    networkName,
                    List.of(),
                    null,
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    labels,
                    Map.of(),
                    Map.of());

            String containerId = createContainer(client, spec);
            containerIds.add(containerId);

            assertThatCode(() -> startContainer(client, containerId)).doesNotThrowAnyException();

            client.destroyContainer(containerId);
            containerIds.remove(containerId);

            client.destroyNetwork(networkId);
            networkIds.remove(networkId);
        }
    }
}
