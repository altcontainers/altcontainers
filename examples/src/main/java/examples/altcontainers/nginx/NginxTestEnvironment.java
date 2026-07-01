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

import examples.support.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.Network;
import org.altcontainers.api.PrefixConsumer;

/**
 * Manages the lifecycle of an Nginx container for parameterized integration tests.
 * Starts an Nginx server inside a Docker container and provides access to the
 * container for port mapping.
 *
 * <p>The caller owns the Docker network and passes it to {@link #initialize(Network)}.
 * {@link #close()} stops only the container; the caller is responsible for closing
 * the network.
 *
 * <p>The container is stopped silently on failure during initialization.
 */
public class NginxTestEnvironment implements AutoCloseable {

    private final String dockerImageName;

    private final String argumentName;

    private volatile Container container;

    /**
     * Creates a test environment for the given Docker image.
     *
     * @param dockerImageName the Nginx Docker image (e.g. {@code "nginx:1.25"})
     */
    public NginxTestEnvironment(final String dockerImageName) {
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
     * Starts the Nginx container on the given Docker network and waits for it to
     * serve HTTP requests. The caller owns the network; {@link #close()} stops only
     * the container.
     *
     * <p>If startup fails, the container is stopped silently before the exception
     * is re-thrown.
     *
     * @param network the Docker network to attach the container to
     */
    public void initialize(final Network network) {
        Objects.requireNonNull(network, "network is null");

        ContainerSpec containerSpec = ContainerSpec.builder(dockerImageName)
                .command("nginx", "-g", "daemon off;")
                .exposePorts(80)
                .network(network, "nginx2")
                .startupAttempts(3)
                .logConsumer(PrefixConsumer.of(getClass().getSimpleName(), argumentName))
                .startupTimeout(Duration.ofSeconds(30))
                .waitForHttpResponse(80, "/")
                .build();

        try {
            container = Container.create(containerSpec);
        } catch (Exception e) {
            stopQuietly();
            throw e;
        }
    }

    /**
     * Returns whether the Nginx container is currently running.
     *
     * @return {@code true} if the container has been started and not yet stopped
     */
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    /**
     * Returns the underlying container instance for direct access, e.g. to
     * retrieve mapped ports.
     *
     * @return the container handle
     */
    public Container getContainer() {
        return container;
    }

    /**
     * Stops the Nginx container silently, suppressing any exceptions. Safe to call
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
     * Creates one {@link NginxTestEnvironment} per Nginx Docker image listed in the
     * {@code /docker-images.txt} classpath resource.
     *
     * @return list of test environments, one per image version
     * @throws IOException if the resource file cannot be read
     */
    public static List<NginxTestEnvironment> createTestEnvironments() throws IOException {
        return Resource.load(NginxTestEnvironment.class, "/docker-images.txt").stream()
                .map(NginxTestEnvironment::new)
                .toList();
    }
}
