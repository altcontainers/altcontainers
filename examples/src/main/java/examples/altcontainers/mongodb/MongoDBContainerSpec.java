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

import java.time.Duration;
import java.util.function.Consumer;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.GenericContainerSpec;
import org.altcontainers.api.Network;

/**
 * A {@link ContainerSpec} pre-configured for a {@code mongo} image.
 *
 * <p>The spec pre-fills MongoDB-specific configuration: exposed port 27017, a
 * port-wait condition for readiness, a 1-minute startup timeout, and 3 startup
 * attempts. No environment variables or custom commands are set — MongoDB image
 * defaults are used.
 *
 * <p>Use {@link #builder(String)} to create a {@code MongoDBContainerSpec}:
 *
 * <pre>{@code
 * MongoDBContainerSpec spec = MongoDBContainerSpec.builder("mongo:7.0")
 *         .network(network, "mongodb")
 *         .logConsumer(ContainerConsumer.of("mongodb", "7.0"))
 *         .build();
 * Container container = Container.create(spec);
 * }</pre>
 */
public final class MongoDBContainerSpec extends GenericContainerSpec {

    /**
     * The MongoDB client port exposed to the host.
     */
    public static final int MONGODB_PORT = 27017;

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(1);
    private static final int STARTUP_ATTEMPTS = 3;

    private MongoDBContainerSpec(GenericContainerSpec genericContainerSpec) {
        super(genericContainerSpec);
    }

    /**
     * Creates a new builder for a MongoDB container spec.
     *
     * @param image the Docker image name (e.g. {@code "mongo:7.0"})
     * @return a mutable builder pre-configured for MongoDB
     */
    public static Builder builder(String image) {
        return new Builder(image);
    }

    /**
     * Mutable builder for configuring a {@link MongoDBContainerSpec}.
     *
     * <p>The builder pre-fills MongoDB defaults: port 27017, port-wait readiness
     * condition, 1-minute startup timeout, 3 startup attempts. Callers specify
     * only image, network, and optionally a log consumer.
     *
     * <p>Instances are mutable and not thread-safe.
     */
    public static final class Builder {

        private final GenericContainerSpec.Builder delegate;

        private Builder(String image) {
            this.delegate = ContainerSpec.builder(image)
                    .exposePorts(MONGODB_PORT)
                    .startupTimeout(STARTUP_TIMEOUT)
                    .startupAttempts(STARTUP_ATTEMPTS)
                    .waitForContainerPort(MONGODB_PORT);
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
         * Sets the log consumer for container output.
         *
         * @param consumer the log consumer
         * @return this builder
         */
        public Builder logConsumer(Consumer<String> consumer) {
            delegate.logConsumer(consumer);
            return this;
        }

        /**
         * Builds an immutable {@link MongoDBContainerSpec}.
         *
         * @return a new MongoDB container spec
         */
        public MongoDBContainerSpec build() {
            return new MongoDBContainerSpec(delegate.build());
        }
    }
}
