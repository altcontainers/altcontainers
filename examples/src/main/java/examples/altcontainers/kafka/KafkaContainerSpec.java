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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.GenericContainerSpec;
import org.altcontainers.api.Network;
import org.altcontainers.api.OutputFrame;

/**
 * A {@link ContainerSpec} pre-configured for an {@code apache/kafka} image running in KRAFT mode.
 *
 * <p>KRAFT mode eliminates the ZooKeeper dependency by running Kafka with an internal consensus protocol.
 * The spec pre-fills Kafka-specific configuration: exposed port 9092, KRAFT environment variables,
 * a log-message wait condition for broker readiness, a 1-minute startup timeout, and 3 startup attempts.
 *
 * <p>Use {@link #builder(String)} to create a {@code KafkaContainerSpec}:
 *
 * <pre>{@code
 * KafkaContainerSpec spec = KafkaContainerSpec.builder("apache/kafka:3.9.2")
 *         .network(network, "kafka")
 *         .outputConsumer(ContainerConsumer.of("kafka", "3.9.2"))
 *         .build();
 * Container container = Container.create(spec);
 * }</pre>
 */
public final class KafkaContainerSpec extends GenericContainerSpec {

    /**
     * The Kafka client port exposed to the host.
     */
    public static final int KAFKA_PORT = 9092;

    /**
     * Internal controller port (not exposed to host, needed for KRAFT quorum).
     */
    static final int CONTROLLER_PORT = 9094;

    static final long MEMORY = 1536L * 1024 * 1024;

    static final long SHM_SIZE = 256L * 1024 * 1024;

    private static final int STARTUP_ATTEMPTS = 3;
    private static final String CLUSTER_ID = "4L6g3nShT-eMCtK--X86sw";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private static Map<String, String> baseEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CLUSTER_ID", CLUSTER_ID);
        environment.put("KAFKA_NODE_ID", "1");
        environment.put("KAFKA_PROCESS_ROLES", "broker,controller");
        environment.put(
                "KAFKA_LISTENERS",
                "PLAINTEXT://0.0.0.0:" + KAFKA_PORT + ",BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:" + CONTROLLER_PORT);
        environment.put(
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT");
        environment.put("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER");
        environment.put("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
        environment.put("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:" + CONTROLLER_PORT);
        environment.put("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        environment.put("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1");
        environment.put("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
        environment.put("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
        environment.put("KAFKA_LOG_FLUSH_INTERVAL_MESSAGES", String.valueOf(Long.MAX_VALUE));
        environment.put("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

        return environment;
    }

    private KafkaContainerSpec(GenericContainerSpec genericContainerSpec) {
        super(genericContainerSpec);
    }

    /**
     * Creates a new builder for a Kafka container spec.
     *
     * @param image the Docker image name (e.g. {@code "apache/kafka:3.9.2"})
     * @return a mutable builder pre-configured for KRAFT-mode Kafka
     */
    public static Builder builder(String image) {
        return new Builder(image);
    }

    /**
     * Mutable builder for configuring a {@link KafkaContainerSpec}.
     *
     * <p>The builder pre-fills Kafka defaults: port 9092, KRAFT environment variables,
     * log-message wait condition, 1-minute startup timeout, 3 startup attempts.
     * Callers specify only image, network, and output consumer.
     *
     * <p>Instances are mutable and not thread-safe.
     */
    public static final class Builder {

        private final GenericContainerSpec.Builder delegate;

        private Builder(String image) {
            this.delegate = ContainerSpec.builder(image)
                    .command(
                            "sh",
                            "-c",
                            "while [ ! -f /tmp/testcontainers_start.sh ]; do sleep 0.1; done;"
                                    + " /tmp/testcontainers_start.sh")
                    .exposePorts(KAFKA_PORT)
                    .memory(MEMORY)
                    .shmSize(SHM_SIZE)
                    .startupTimeout(STARTUP_TIMEOUT)
                    .startupAttempts(STARTUP_ATTEMPTS)
                    .waitForLogMessage(".*Transition(?:ing)? from RECOVERY to RUNNING.*")
                    .prepare(container -> {
                        String host = container.host();
                        int hostPort = container.hostPort(KAFKA_PORT);
                        String script = "#!/bin/bash\n"
                                + "export KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://"
                                + host
                                + ":"
                                + hostPort
                                + ",BROKER://localhost:9093\n"
                                + "exec /etc/kafka/docker/run\n";
                        container.copyFileToContainer(
                                "/tmp", "testcontainers_start.sh", script.getBytes(StandardCharsets.UTF_8), 0777);
                    });
        }

        /**
         * Joins the container to a Docker network with the given DNS alias.
         *
         * @param network the network to join
         * @param alias DNS alias within the network
         * @return this builder
         */
        public Builder network(Network network, String alias) {
            delegate.network(network, alias);
            return this;
        }

        /**
         * Sets the output consumer for container output.
         *
         * @param consumer the output consumer
         * @return this builder
         */
        public Builder outputConsumer(Consumer<OutputFrame> consumer) {
            delegate.outputListener(consumer);
            return this;
        }

        /**
         * Builds an immutable {@link KafkaContainerSpec}.
         *
         * @return a new Kafka container spec
         */
        public KafkaContainerSpec build() {
            delegate.environment(baseEnvironment());
            return new KafkaContainerSpec(delegate.build());
        }
    }
}
