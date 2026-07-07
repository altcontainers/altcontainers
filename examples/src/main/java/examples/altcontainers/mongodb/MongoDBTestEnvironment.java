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

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import examples.altcontainers.support.ContainerConsumer;
import examples.support.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;

/**
 * Manages the lifecycle of a MongoDB container for parameterized integration tests.
 * Starts a single-node MongoDB instance inside a Docker container and provides
 * the connection string for client access.
 *
 * <p>The caller owns the Docker network and passes it to {@link #initialize(Network)}.
 * {@link #close()} stops only the container; the caller is responsible for closing
 * the network.
 *
 * <p>The container is stopped silently on failure during initialization.
 */
public class MongoDBTestEnvironment implements AutoCloseable {

    private final String dockerImageName;

    private final String argumentName;

    private volatile Container container;

    /**
     * Creates a test environment for the given Docker image.
     *
     * @param dockerImageName the MongoDB Docker image (e.g. {@code "mongo:7.0"})
     */
    public MongoDBTestEnvironment(final String dockerImageName) {
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
     * Starts the MongoDB container on the given Docker network and waits for the
     * database to accept connections. The caller owns the network; {@link #close()}
     * stops only the container.
     *
     * <p>If startup fails, the container is stopped silently before the exception
     * is re-thrown.
     *
     * @param network the Docker network to attach the container to
     */
    public void initialize(final Network network) {
        Objects.requireNonNull(network, "network must not be null");

        MongoDBContainerSpec spec = MongoDBContainerSpec.builder(dockerImageName)
                .network(network, argumentName)
                .outputConsumer(ContainerConsumer.of(getClass().getSimpleName(), argumentName))
                .build();

        try {
            container = Container.create(spec);
        } catch (ContainerException e) {
            stopQuietly();
            throw e;
        }
        awaitBrokerReady();
    }

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
                throw new ContainerException("Interrupted while waiting for MongoDB", e);
            }
        }
        stopQuietly();
        throw new ContainerException("MongoDB not ready within startup timeout");
    }

    private boolean isBrokerReady() {
        try {
            var settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(getConnectionString()))
                    .applyToClusterSettings(builder -> builder.serverSelectionTimeout(2, TimeUnit.SECONDS))
                    .build();
            try (MongoClient client = MongoClients.create(settings)) {
                client.listDatabaseNames().first();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns whether the MongoDB container is currently running.
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
     * Returns the MongoDB connection string for connecting clients to this instance.
     *
     * @return the connection string (e.g. {@code "mongodb://localhost:12345"})
     * @throws IllegalStateException if called before {@link #initialize(Network)}
     */
    public String getConnectionString() {
        if (container == null) {
            throw new IllegalStateException("getConnectionString() called before initialize()");
        }
        return "mongodb://" + container.host() + ":" + container.hostPort(MongoDBContainerSpec.MONGODB_PORT);
    }

    /**
     * Stops the MongoDB container silently, suppressing any exceptions. Safe to call
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
     * Creates one {@link MongoDBTestEnvironment} per MongoDB Docker image listed in the
     * {@code /docker-images.txt} classpath resource.
     *
     * @return list of test environments, one per image version
     * @throws IOException if the resource file cannot be read
     */
    public static List<MongoDBTestEnvironment> createTestEnvironments() throws IOException {
        List<String> images = Resource.load(MongoDBTestEnvironment.class, "/docker-images.txt");
        List<MongoDBTestEnvironment> environments = new ArrayList<>();
        for (String image : images) {
            environments.add(new MongoDBTestEnvironment(image));
        }
        return environments;
    }
}
