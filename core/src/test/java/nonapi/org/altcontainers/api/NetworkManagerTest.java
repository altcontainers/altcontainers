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

import org.altcontainers.api.Network;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NetworkManager}.
 */
class NetworkManagerTest {

    @Test
    void doubleCloseShouldReleasePermitOnlyOnce() {
        NetworkManager manager = new NetworkManager(1);
        int permitsBefore = manager.availableNetworkPermitsForTesting();
        // A network id that does not exist in the daemon: closeNetwork handles the
        // not-found result and must still release at most one semaphore permit.
        Network network = new ConcreteNetwork("test-net", "nonexistent-network-id");
        manager.closeNetwork(network);
        manager.closeNetwork(network);
        assertThat(manager.availableNetworkPermitsForTesting())
                .as("closing the same network twice must release exactly one permit")
                .isEqualTo(permitsBefore + 1);
    }
}
