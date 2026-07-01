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

package nonapi.org.altcontainers.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nonapi.org.altcontainers.ContainerCreateSpec;
import nonapi.org.altcontainers.DockerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ResourceCleanerIntegrationTest {

    private static final String TEST_IMAGE = "alpine:latest";

    private static DockerClient client;

    private final List<String> containerIds = new ArrayList<>();
    private final List<String> networkIds = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        client = DockerClient.instance();
        client.pullImageIfMissing(TEST_IMAGE);
    }

    @AfterEach
    void tearDown() {
        for (String id : containerIds) {
            try {
                client.forceRemoveContainer(id);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        for (String id : networkIds) {
            try {
                client.removeNetwork(id);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        containerIds.clear();
        networkIds.clear();
    }

    @Test
    void cleanupSession_removesContainers() {
        String sessionId = UUID.randomUUID().toString();
        String containerId = createTestContainer(sessionLabels(sessionId));
        containerIds.add(containerId);
        assertThat(client.containerExists(containerId)).isTrue();

        ResourceCleaner.cleanupSession(client, sessionId, 10_000L);

        assertThat(client.containerExists(containerId)).isFalse();
        containerIds.remove(containerId);
    }

    @Test
    void cleanupSession_removesNetworks() {
        String sessionId = UUID.randomUUID().toString();
        String networkId = createTestNetwork(sessionLabels(sessionId));
        networkIds.add(networkId);
        assertThat(client.networkExists(networkId)).isTrue();

        ResourceCleaner.cleanupSession(client, sessionId, 10_000L);

        assertThat(client.networkExists(networkId)).isFalse();
        networkIds.remove(networkId);
    }

    @Test
    void cleanupSession_handlesEmptySession() {
        ResourceCleaner.cleanupSession(client, UUID.randomUUID().toString(), 10_000L);
    }

    @Test
    void cleanupSession_preservesResourcesFromOtherSessions() {
        String sessionA = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();
        String containerA = createTestContainer(sessionLabels(sessionA));
        String containerB = createTestContainer(sessionLabels(sessionB));
        containerIds.add(containerA);
        containerIds.add(containerB);

        ResourceCleaner.cleanupSession(client, sessionA, 10_000L);

        assertThat(client.containerExists(containerA)).isFalse();
        assertThat(client.containerExists(containerB)).isTrue();
        containerIds.remove(containerA);
    }

    private static Map<String, String> sessionLabels(String sessionId) {
        return java.util.Map.of(
                ResourceLabels.MANAGED,
                "true",
                ResourceLabels.SESSION_ID,
                sessionId,
                ResourceLabels.CREATED_AT_MS,
                String.valueOf(System.currentTimeMillis()));
    }

    private String createTestContainer(Map<String, String> labels) {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                TEST_IMAGE,
                List.of("sleep", "infinity"),
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
                labels);
        return client.createContainer(spec);
    }

    private String createTestNetwork(Map<String, String> labels) {
        return client.createNetwork("altcontainers-test-" + UUID.randomUUID(), labels);
    }
}
