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

package org.altcontainers.api;

import java.util.Objects;
import nonapi.org.altcontainers.ContainerManager;
import nonapi.org.altcontainers.DockerClient;

/**
 * A runtime handle to a started Docker container.
 *
 * <p>{@code Container} is an immutable facade: it holds only the container identifier and image and
 * delegates every Docker operation to the shared, package-private {@link DockerClient}. Instances are
 * thread-safe handles that can be shared freely.
 *
 * <p>Containers are created via {@link #create(ContainerSpec)} and must be
 * released with {@link #destroy()} (or {@link #close()}, which delegates to {@code destroy()}). Use
 * try-with-resources to guarantee cleanup:
 *
 * <pre>{@code
 * ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
 *         .exposePorts(8080)
 *         .waitForLogMessage("started")
 *         .build();
 * try (Container container = Container.create(spec)) {
 *     // ... use container ...
 * }
 * }</pre>
 *
 * <p>Destruction is idempotent and blocks until Docker confirms the container is gone.
 */
public class Container implements AutoCloseable {

    /**
     * The Docker-assigned container identifier.
     */
    private final String id;

    /**
     * The Docker image the container runs.
     */
    private final String image;

    /**
     * Creates a container handle.
     *
     * @param id the Docker-assigned container identifier; must not be {@code null}
     * @param image the Docker image name; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Container(String id, String image) {
        this.id = Objects.requireNonNull(id);
        this.image = Objects.requireNonNull(image);
    }

    /**
     * Creates, starts, and waits for a container to become ready, returning a runtime handle.
     *
     * <p>Delegates to the internal container manager. Equivalent to the former
     * {@code Container.create(spec)}.
     *
     * @param containerSpec the immutable desired container configuration; must not be {@code null}
     * @return a handle to the started, ready container
     * @throws NullPointerException if {@code containerSpec} is {@code null}
     * @throws IllegalArgumentException if the spec's {@code startupAttempts < 1} or
     *     {@code startupTimeout} is not positive
     * @throws ContainerException if the container cannot be pulled, started, or reach a ready state
     */
    public static Container create(ContainerSpec containerSpec) {
        return ContainerManager.getInstance().createContainer(containerSpec);
    }

    /**
     * Stops and removes the given container, blocking until Docker confirms it is gone. Null-safe.
     *
     * @param container the container to destroy, or {@code null} for a no-op
     * @throws ContainerException if the container is not confirmed gone within the destroy deadline,
     *     or if the calling thread is interrupted while waiting
     */
    public static void destroy(Container container) {
        ContainerManager.getInstance().destroyContainer(container);
    }

    /**
     * Returns the Docker-assigned container identifier.
     *
     * @return the container identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the Docker image the container runs.
     *
     * @return the Docker image name
     */
    public String image() {
        return image;
    }

    /**
     * Returns whether the container is currently in the {@code running} state.
     *
     * <p>Absent containers are reported as not running rather than throwing.
     *
     * @return {@code true} if the container exists and is running; {@code false} otherwise
     */
    public boolean isRunning() {
        return DockerClient.instance().isContainerRunning(id);
    }

    /**
     * Returns the mapped host port for the given container port.
     *
     * @param containerPort the port exposed inside the container
     * @return the host port mapped to the container port, or {@code -1} if not found, the container is
     *     gone, or the port specification is malformed
     */
    public int hostPort(int containerPort) {
        return DockerClient.instance().hostPort(id, containerPort);
    }

    /**
     * Stop and remove this container, blocking until Docker confirms it is gone. Idempotent and terminal.
     *
     * <p>Releases the container's log follow-stream, issues a best-effort graceful stop, then an idempotent
     * force-remove, polling until the container can no longer be inspected. Returns only once destruction
     * is confirmed. The container cannot be reused after this call.
     *
     * @throws ContainerException if the container is not confirmed gone within the destroy deadline,
     *     or if the calling thread is interrupted while waiting
     */
    public void destroy() {
        ContainerManager.getInstance().destroyContainer(this);
    }

    /**
     * Destroys this container. Delegates to {@link #destroy()}. Implements {@link AutoCloseable} for use
     * in try-with-resources blocks.
     */
    @Override
    public void close() {
        destroy();
    }
}
