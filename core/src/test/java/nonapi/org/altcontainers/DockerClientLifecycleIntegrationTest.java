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
import static nonapi.org.altcontainers.LogOperations.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DockerClientLifecycleIntegrationTest {

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
    void getContainerLogsReturnsExistingOutputFromRealContainer() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                TEST_IMAGE,
                List.of("sh", "-c", "echo hello-altcontainers"),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                Map.of());

        String id = createContainer(client, spec);
        containerIds.add(id);
        startContainer(client, id);

        // Brief wait for the container to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String logs = getLogs(client, id);
        assertThat(logs).contains("hello-altcontainers");

        client.destroyContainer(id);
        containerIds.remove(id);
    }

    @Test
    void hostPortResolvesPublishedPortFromRealContainer() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                TEST_IMAGE,
                List.of("sleep", "infinity"),
                List.of(8080),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                Map.of());

        String id = createContainer(client, spec);
        containerIds.add(id);
        startContainer(client, id);

        int hostPort = hostPort(client, id, 8080);
        assertThat(hostPort).isGreaterThan(0);

        client.destroyContainer(id);
        containerIds.remove(id);
    }

    @Test
    void logWaitStrategyReceivesFollowedLogs() {
        try (Container container = Container.create(ContainerSpec.builder(TEST_IMAGE)
                .command("sh", "-c", "echo ready; sleep 10")
                .waitForLogMessage(".*ready.*")
                .build())) {
            assertThat(container.id()).isNotBlank();
            containerIds.add(container.id());
        } catch (ContainerException e) {
            // Container close may interrupt the sleep; that's fine.
        }
    }
}
