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

package examples.altcontainers.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import nonapi.org.altcontainers.api.ConcreteContainer;
import nonapi.org.altcontainers.api.ConcreteNetwork;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.LogWaitStrategy;
import org.altcontainers.api.Network;
import org.altcontainers.api.OutputFrame;
import org.junit.jupiter.api.Test;

class KafkaContainerSpecTest {

    private static final String IMAGE = "apache/kafka:3.9.2";
    private static final Network NETWORK = new ConcreteNetwork("test-net", "test-id");

    @Test
    void shouldSetImage() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldExposeKafkaPort() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.exposedPorts()).contains(KafkaContainerSpec.KAFKA_PORT);
    }

    @Test
    void shouldSetMemoryLimit() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.memory()).isEqualTo(1536L * 1024 * 1024);
    }

    @Test
    void shouldSetShmSize() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.shmSize()).isEqualTo(256L * 1024 * 1024);
    }

    @Test
    void shouldSetKraftEnvironment() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.environment())
                .containsEntry("CLUSTER_ID", "4L6g3nShT-eMCtK--X86sw")
                .containsEntry("KAFKA_NODE_ID", "1")
                .containsEntry("KAFKA_PROCESS_ROLES", "broker,controller")
                .containsEntry(
                        "KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094")
                .containsEntry(
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
                .containsEntry("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER")
                .containsEntry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .containsEntry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .containsEntry("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1")
                .containsEntry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .containsEntry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                .containsEntry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9094")
                .containsEntry("KAFKA_LOG_FLUSH_INTERVAL_MESSAGES", String.valueOf(Long.MAX_VALUE))
                .containsEntry("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
    }

    @Test
    void shouldSetNetworkConfig() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.networkMode()).isEqualTo("test-net");
        assertThat(spec.networkAliases()).containsExactly("kafka");
    }

    @Test
    void shouldHaveLogWaitCondition() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.waitConditions()).hasSize(1);
        assertThat(spec.waitConditions().get(0)).isInstanceOf(LogWaitStrategy.class);
    }

    @Test
    void shouldSetStartupTimeout() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.startupTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(spec.startupAttempts()).isEqualTo(3);
    }

    @Test
    void shouldPassOutputConsumer() {
        Consumer<OutputFrame> consumer = frame -> {};
        var spec = KafkaContainerSpec.builder(IMAGE)
                .network(NETWORK, "kafka")
                .outputConsumer(consumer)
                .build();
        assertThat(spec.outputListener()).isSameAs(consumer);
    }

    @Test
    void shouldBeAssignableToContainerSpec() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        ContainerSpec containerSpec = spec;
        assertThat(containerSpec).isNotNull();
    }

    @Test
    void shouldUseRandomPort() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.exposedPorts()).contains(KafkaContainerSpec.KAFKA_PORT);
        assertThat(spec.portBindings()).isEmpty();
    }

    @Test
    void shouldNotSetAdvertisedListenersInEnvironment() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.environment()).doesNotContainKey("KAFKA_ADVERTISED_LISTENERS");
    }

    @Test
    void shouldSetWaitLoopCommand() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        assertThat(spec.command()).isNotEmpty();
        assertThat(spec.command().get(2)).contains("/tmp/testcontainers_start.sh");
    }

    @Test
    void shouldBuildBootstrapServersWithContainerHost() throws Exception {
        var environment = new KafkaTestEnvironment(IMAGE);
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
                        return 32769;
                    }
                });

        assertThat(environment.getBootstrapServers()).isEqualTo("docker.example.test:32769");
    }

    @Test
    void shouldAdvertiseContainerHostForExternalListener() {
        var spec = KafkaContainerSpec.builder(IMAGE).network(NETWORK, "kafka").build();
        AtomicReference<String> script = new AtomicReference<>();

        ConcreteContainer mockContainer =
                new ConcreteContainer(
                        "test-id", IMAGE, ContainerSpec.builder(IMAGE).build(), null) {
                    @Override
                    public String host() {
                        return "docker.example.test";
                    }

                    @Override
                    public Integer hostPort(int containerPort) {
                        return 32770;
                    }

                    @Override
                    public void copyFileToContainer(String containerPath, String fileName, byte[] content, int mode) {
                        script.set(new String(content, StandardCharsets.UTF_8));
                    }
                };

        Consumer<Container> prepare = spec.prepare();
        if (prepare != null) {
            prepare.accept(mockContainer);
        }

        assertThat(script.get())
                .contains("KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://docker.example.test:32770")
                .contains("BROKER://localhost:9093");
    }

    private static void setContainer(KafkaTestEnvironment environment, Container container) throws Exception {
        var field = KafkaTestEnvironment.class.getDeclaredField("container");
        field.setAccessible(true);
        field.set(environment, container);
    }
}
