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

import java.time.Duration;
import java.util.function.Consumer;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.LogWaitStrategy;
import org.altcontainers.api.Network;
import org.junit.jupiter.api.Test;

class KafkaContainerSpecTest {

    private static final String IMAGE = "apache/kafka:3.9.2";
    private static final Network NETWORK = new Network("test-net", "test-id");

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
    void shouldPassLogConsumer() {
        Consumer<String> consumer = line -> {};
        var spec = KafkaContainerSpec.builder(IMAGE)
                .network(NETWORK, "kafka")
                .logConsumer(consumer)
                .build();
        assertThat(spec.logConsumer()).isSameAs(consumer);
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
}
