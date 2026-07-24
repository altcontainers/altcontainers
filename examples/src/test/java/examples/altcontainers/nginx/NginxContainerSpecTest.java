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

package examples.altcontainers.nginx;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Consumer;
import nonapi.org.altcontainers.api.ConcreteNetwork;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.HttpWaitStrategy;
import org.altcontainers.api.Network;
import org.altcontainers.api.OutputFrame;
import org.junit.jupiter.api.Test;

class NginxContainerSpecTest {

    private static final String IMAGE = "nginx:1.27.5";
    private static final Network NETWORK = new ConcreteNetwork("test-net", "test-id");

    @Test
    void shouldSetImage() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldExposeNginxPort() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.exposedPorts()).contains(NginxContainerSpec.NGINX_PORT);
    }

    @Test
    void shouldSetCommand() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.command()).containsExactly("nginx", "-g", "daemon off;");
    }

    @Test
    void shouldSetNetworkConfig() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.networkMode()).isEqualTo("test-net");
        assertThat(spec.networkAliases()).containsExactly("nginx2");
    }

    @Test
    void shouldHaveHttpWaitCondition() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.waitConditions()).hasSize(1);
        assertThat(spec.waitConditions().get(0)).isInstanceOf(HttpWaitStrategy.class);
    }

    @Test
    void shouldSetStartupTimeout() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.startupTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(spec.startupAttempts()).isEqualTo(3);
    }

    @Test
    void shouldPassOutputConsumer() {
        Consumer<OutputFrame> consumer = frame -> {};
        var spec = NginxContainerSpec.builder(IMAGE)
                .network(NETWORK, "nginx2")
                .outputConsumer(consumer)
                .build();
        assertThat(spec.outputListener()).isSameAs(consumer);
    }

    @Test
    void shouldBeAssignableToContainerSpec() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        ContainerSpec containerSpec = spec;
        assertThat(containerSpec).isNotNull();
    }

    @Test
    void shouldUseRandomPort() {
        var spec = NginxContainerSpec.builder(IMAGE).network(NETWORK, "nginx2").build();
        assertThat(spec.exposedPorts()).contains(NginxContainerSpec.NGINX_PORT);
        assertThat(spec.portBindings()).isEmpty();
    }
}
