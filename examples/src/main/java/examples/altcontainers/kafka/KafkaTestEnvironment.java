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

import examples.altcontainers.support.ContainerConsumer;
import examples.support.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

/**
 * Manages the lifecycle of a Kafka container for parameterized integration tests.
 * Starts a single-node Kafka broker inside a Docker container using KRAFT mode
 * and provides methods to create topics and retrieve connection details.
 *
 * <p>The caller owns the Docker network and passes it to {@link #initialize(Network)}.
 * {@link #close()} stops only the container; the caller is responsible for closing
 * the network.
 *
 * <p>After the container starts, the broker is polled until it accepts API
 * requests, or the startup timeout elapses.
 *
 * <p>The container is stopped silently on failure during initialization.
 */
public class KafkaTestEnvironment implements AutoCloseable {

    private static final String ADMIN_REQUEST_TIMEOUT_MS = "5000";
    private static final int TOPIC_CREATE_TIMEOUT_SECONDS = 8;

    private final String dockerImageName;

    private final String argumentName;

    private volatile Container container;

    /**
     * Creates a test environment for the given Docker image.
     *
     * @param dockerImageName the Kafka Docker image (e.g. {@code "apache/kafka:3.9.2"})
     */
    public KafkaTestEnvironment(final String dockerImageName) {
        this.dockerImageName = dockerImageName;
        this.argumentName = dockerImageName.replace("[", "").replace("]", "");
    }

    /**
     * Returns the display name derived from the Docker image, with bracket characters removed.
     *
     * @return the argument name used for test identification
     */
    public String name() {
        return argumentName;
    }

    /**
     * Starts the Kafka container on the given Docker network, waits for the broker
     * to become ready, and polls until the broker accepts API requests.
     *
     * <p>If startup fails, the container is stopped silently before the exception
     * is re-thrown.
     *
     * @param network the Docker network to attach the container to
     */
    public void initialize(final Network network) {
        Objects.requireNonNull(network, "network must not be null");

        KafkaContainerSpec spec = KafkaContainerSpec.builder(dockerImageName)
                .startupDirectory("ignored")
                .network(network, argumentName.replace('/', '-').replace(':', '-'))
                .logConsumer(ContainerConsumer.of(getClass().getSimpleName(), argumentName))
                .build();

        try {
            container = Container.create(spec);
        } catch (ContainerException e) {
            stopQuietly();
            throw e;
        }
        awaitBrokerReady();
    }

    /**
     * Polls the Kafka broker until it accepts API requests, or the broker
     * startup timeout elapses.
     */
    private void awaitBrokerReady() {
        int pollIntervalMs = 500;
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (isBrokerReady()) {
                return;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while waiting for Kafka broker", e);
            }
        }
        stopQuietly();
        throw new ContainerException("Kafka broker not ready within startup timeout");
    }

    /**
     * Tests whether the Kafka broker is ready by attempting to create a
     * temporary topic.
     *
     * @return {@code true} if the broker accepted the topic creation request
     */
    private boolean isBrokerReady() {
        try {
            createTopic("_altcontainers_readiness_probe");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the {@code bootstrap.servers} URL for connecting Kafka clients to this broker.
     *
     * @return the bootstrap servers string
     * @throws IllegalStateException if called before {@link #initialize(Network)}
     */
    public String getBootstrapServers() {
        if (container == null) {
            throw new IllegalStateException("getBootstrapServers() called before initialize()");
        }
        return "localhost:" + container.hostPort(KafkaContainerSpec.KAFKA_PORT);
    }

    /**
     * Returns whether the Kafka container is currently running.
     *
     * @return {@code true} if the container has been started and not yet stopped
     */
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    /**
     * Returns the underlying container instance for direct access.
     *
     * @return the container handle, or {@code null} if not yet initialized
     */
    public Container getContainer() {
        return container;
    }

    /**
     * Creates a Kafka topic with a single partition and replication factor of 1.
     * Silently succeeds if the topic already exists.
     *
     * @param topic the topic name to create
     * @throws ExecutionException if an asynchronous admin operation fails (other than topic-exists)
     * @throws InterruptedException if the calling thread is interrupted; the interrupt flag is restored
     * @throws IllegalStateException if the create operation times out
     */
    public void createTopic(final String topic) throws ExecutionException, InterruptedException {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ADMIN_REQUEST_TIMEOUT_MS);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, ADMIN_REQUEST_TIMEOUT_MS);

        try (var adminClient = AdminClient.create(properties)) {
            adminClient
                    .createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all()
                    .get(TOPIC_CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out creating topic: " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof TopicExistsException
                    || (cause != null && cause.getCause() instanceof TopicExistsException)) {
                return;
            }

            throw e;
        }
    }

    /**
     * Stops the Kafka container silently, suppressing any exceptions. Safe to call
     * multiple times or when the container was never started.
     */
    public void close() {
        stopQuietly();
    }

    private void stopQuietly() {
        if (container != null) {
            try {
                container.close();
            } catch (Exception ignored) {
                // Intentionally suppress stop exception to preserve original cause
            } finally {
                container = null;
            }
        }
    }

    /**
     * Creates one {@link KafkaTestEnvironment} per Kafka Docker image listed in the
     * {@code /docker-images.txt} classpath resource.
     *
     * @return list of test environments, one per image version
     * @throws IOException if the resource file cannot be read
     */
    public static List<KafkaTestEnvironment> createTestEnvironments() throws IOException {
        List<String> images = Resource.load(KafkaTestEnvironment.class, "/docker-images.txt");
        List<KafkaTestEnvironment> environments = new ArrayList<>();
        for (String image : images) {
            environments.add(new KafkaTestEnvironment(image));
        }
        return environments;
    }
}
