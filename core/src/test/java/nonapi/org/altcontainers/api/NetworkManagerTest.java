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

import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.altcontainers.api.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for {@link NetworkManager}.
 */
class NetworkManagerTest {

    static boolean dockerAndReaperAvailable() {
        return ContainerManagerPullTest.dockerAndReaperAvailable();
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void doubleCloseShouldReleasePermitOnlyOnce() {
        NetworkManager manager = new NetworkManager(1);
        Network network = manager.createNetwork();
        try {
            assertThat(manager.availableNetworkPermitsForTesting()).isZero();
        } finally {
            manager.closeNetwork(network);
        }
        assertThat(manager.availableNetworkPermitsForTesting()).isOne();

        manager.closeNetwork(network);
        assertThat(manager.availableNetworkPermitsForTesting())
                .as("closing the same network twice must release exactly one permit")
                .isOne();
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void closingAnUnmanagedNetworkShouldNotReleaseAPermit() {
        NetworkManager owner = new NetworkManager(1);
        NetworkManager manager = new NetworkManager(1);
        Network network = owner.createNetwork();
        try {
            manager.closeNetwork(network);
            assertThatThrownBy(() -> DockerClientFactory.client()
                            .inspectNetworkCmd()
                            .withNetworkId(network.id())
                            .exec())
                    .as("the manager must still remove the unmanaged network")
                    .isInstanceOf(NotFoundException.class);
            assertThat(manager.availableNetworkPermitsForTesting()).isOne();
            assertThat(owner.availableNetworkPermitsForTesting()).isZero();
        } finally {
            owner.closeNetwork(network);
        }
        assertThat(owner.availableNetworkPermitsForTesting()).isOne();
    }

    @Test
    void activeEndpointsFailureShouldBeRecognized() {
        DockerException activeEndpoints = new DockerException(
                "Status 403: {\"message\":\"error while removing network: network altcontainers-0928f72f-43fb5315"
                        + " has active endpoints (name:\\\"lucid_shtern\\\" id:\\\"cf4f241cd6b6\\\")\"}",
                403);

        assertThat(NetworkManager.isActiveEndpointsFailure(activeEndpoints)).isTrue();
    }

    @Test
    void otherNetworkRemovalFailuresShouldNotBeTreatedAsActiveEndpoints() {
        assertThat(NetworkManager.isActiveEndpointsFailure(
                        new DockerException("Status 500: {\"message\":\"internal error\"}", 500)))
                .isFalse();
        assertThat(NetworkManager.isActiveEndpointsFailure(new NotFoundException("network not found")))
                .isFalse();
        assertThat(NetworkManager.isActiveEndpointsFailure(new IllegalStateException("has active endpoints")))
                .as("only Docker API failures qualify")
                .isFalse();
        assertThat(NetworkManager.isActiveEndpointsFailure(new DockerException(null, 403)))
                .isFalse();
        assertThat(NetworkManager.isActiveEndpointsFailure(null)).isFalse();
    }
}
