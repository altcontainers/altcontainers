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

package examples.altcontainers.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Consumer;
import nonapi.org.altcontainers.api.ConcreteContainer;
import nonapi.org.altcontainers.api.ConcreteNetwork;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.Network;
import org.altcontainers.api.OutputFrame;
import org.altcontainers.api.PortWaitStrategy;
import org.junit.jupiter.api.Test;

class MongoDBContainerSpecTest {

    private static final String IMAGE = "mongo:7.0";
    private static final Network NETWORK = new ConcreteNetwork("test-net", "test-id");

    @Test
    void shouldSetImage() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldExposeMongoDBPort() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.exposedPorts()).contains(MongoDBContainerSpec.MONGODB_PORT);
    }

    @Test
    void shouldSetNetworkConfig() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.networkMode()).isEqualTo("test-net");
        assertThat(spec.networkAliases()).containsExactly("mongodb");
    }

    @Test
    void shouldHavePortWaitCondition() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.waitConditions()).hasSize(1);
        assertThat(spec.waitConditions().get(0)).isInstanceOf(PortWaitStrategy.class);
    }

    @Test
    void shouldSetStartupTimeout() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.startupTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(spec.startupAttempts()).isEqualTo(3);
    }

    @Test
    void shouldPassOutputConsumer() {
        Consumer<OutputFrame> consumer = frame -> {};
        var spec = MongoDBContainerSpec.builder(IMAGE)
                .network(NETWORK, "mongodb")
                .outputConsumer(consumer)
                .build();
        assertThat(spec.outputListener()).isSameAs(consumer);
    }

    @Test
    void shouldBeAssignableToContainerSpec() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        ContainerSpec containerSpec = spec;
        assertThat(containerSpec).isNotNull();
    }

    @Test
    void shouldUseRandomPort() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.exposedPorts()).contains(MongoDBContainerSpec.MONGODB_PORT);
        assertThat(spec.portBindings()).isEmpty();
    }

    @Test
    void shouldHaveNoEnvironmentVariables() {
        var spec =
                MongoDBContainerSpec.builder(IMAGE).network(NETWORK, "mongodb").build();
        assertThat(spec.environment()).isEmpty();
    }

    @Test
    void shouldBuildConnectionStringWithContainerHost() throws Exception {
        var environment = new MongoDBTestEnvironment(IMAGE);
        setContainer(
                environment,
                new ConcreteContainer(
                        "test-id", IMAGE, ContainerSpec.builder(IMAGE).build(), null) {
                    @Override
                    public String host() {
                        return "docker.example.test";
                    }

                    @Override
                    public Integer hostPort(int containerPort) {
                        return 32768;
                    }
                });

        assertThat(environment.getConnectionString()).isEqualTo("mongodb://docker.example.test:32768");
    }

    private static void setContainer(MongoDBTestEnvironment environment, Container container) throws Exception {
        var field = MongoDBTestEnvironment.class.getDeclaredField("container");
        field.setAccessible(true);
        field.set(environment, container);
    }
}
