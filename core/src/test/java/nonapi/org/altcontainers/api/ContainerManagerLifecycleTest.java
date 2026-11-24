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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.exception.NotFoundException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.Network;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Tests container cleanup when startup or stop fails. */
@Tag("docker")
class ContainerManagerLifecycleTest {

    static boolean dockerAndReaperAvailable() {
        return ContainerManagerPullTest.dockerAndReaperAvailable();
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldCleanupCreatedContainerWhenPostStartInspectionFails() {
        ContainerManager manager = ContainerManager.getInstance();
        AtomicReference<String> createdId = new AtomicReference<>();
        manager.setMetadataInspectorForTesting(id -> {
            createdId.set(id);
            throw new ContainerException("synthetic metadata failure");
        });
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sleep", "30")
                .startupAttempts(1)
                .build();

        try {
            assertThatThrownBy(() -> Container.create(spec)).isInstanceOf(ContainerException.class);

            String id = createdId.get();
            assertThat(id).isNotNull();
            assertThatThrownBy(() ->
                            DockerClientFactory.client().inspectContainerCmd(id).exec())
                    .isInstanceOf(NotFoundException.class);
        } finally {
            manager.resetMetadataInspectorForTesting();
            String id = createdId.get();
            if (id != null) {
                try {
                    DockerClientFactory.client()
                            .removeContainerCmd(id)
                            .withForce(true)
                            .exec();
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup for a failed reproduction.
                }
            }
        }
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldForceRemoveContainerSynchronouslyWhenStopTimesOut() {
        assertContainerForceRemovedSynchronously((id, dockerTimeout, futureTimeout) -> {
            throw new TimeoutException("synthetic stop timeout");
        });
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldForceRemoveContainerSynchronouslyWhenStopFails() {
        assertContainerForceRemovedSynchronously((id, dockerTimeout, futureTimeout) -> {
            throw new ExecutionException(new RuntimeException("synthetic stop failure"));
        });
    }

    /**
     * Closes a running container attached to a network while the stop step fails, then verifies
     * that the container is already gone when {@code close()} returns and that the network can be
     * removed immediately afterwards (no "has active endpoints" failure).
     *
     * <p>{@code sleep} runs as PID 1 and ignores SIGTERM, matching the real-world case where a
     * graceful stop waits for the full Docker stop timeout.
     *
     * @param failingStopper the stopper that simulates the stop failure
     */
    private static void assertContainerForceRemovedSynchronously(ContainerManager.ContainerStopper failingStopper) {
        ContainerManager manager = ContainerManager.getInstance();
        Network network = Network.create();
        Container container = null;
        try {
            container = Container.create(ContainerSpec.builder("alpine:latest")
                    .command("sleep", "300")
                    .network(network, "sleeper")
                    .startupAttempts(1)
                    .build());
            String containerId = container.id();

            manager.setContainerStopperForTesting(failingStopper);
            container.close();

            assertThatThrownBy(() -> DockerClientFactory.client()
                            .inspectContainerCmd(containerId)
                            .exec())
                    .as("container must be removed before close() returns")
                    .isInstanceOf(NotFoundException.class);

            network.close();

            assertThatThrownBy(() -> DockerClientFactory.client()
                            .inspectNetworkCmd()
                            .withNetworkId(network.id())
                            .exec())
                    .as("network must be removed by close(), not delegated to the reaper")
                    .isInstanceOf(NotFoundException.class);
        } finally {
            manager.resetContainerStopperForTesting();
            if (container != null) {
                try {
                    DockerClientFactory.client()
                            .removeContainerCmd(container.id())
                            .withForce(true)
                            .exec();
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup for a failed reproduction.
                }
            }
            network.close();
        }
    }
}
