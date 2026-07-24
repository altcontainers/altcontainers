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

import nonapi.org.altcontainers.api.ContainerManager;

/**
 * A runtime handle to a started Docker container.
 *
 * <p>Containers are created via {@link #create(ContainerSpec)} and destroyed via
 * {@link #close()} or {@link #close(Container)}. The concrete implementation
 * lives in the {@code nonapi} package and is not intended for direct
 * construction.
 */
public interface Container extends AutoCloseable {

    /**
     * Creates, starts, and waits for a container to become ready.
     *
     * @param containerSpec the container spec
     * @return a container handle
     * @throws ContainerException if creation fails
     */
    static Container create(ContainerSpec containerSpec) {
        return ContainerManager.getInstance().createContainer(containerSpec);
    }

    /**
     * Destroys a container. Null-safe. Delegates to the instance
     * {@link #close()} method which ensures idempotent close.
     *
     * @param container the container to close; may be {@code null}
     */
    static void close(Container container) {
        if (container != null) {
            container.close();
        }
    }

    /**
     * Returns the container id.
     *
     * @return the id
     */
    String id();

    /**
     * Returns the Docker image.
     *
     * @return the image name
     */
    String image();

    /**
     * Returns whether the container exists and is in the running state.
     * Performs a live Docker daemon query on every call.
     *
     * <p>Daemon communication errors (including the container no longer
     * existing) are reported as {@code false}.
     *
     * @return {@code true} if the container is currently running
     */
    boolean isRunning();

    /**
     * Returns the Docker host.
     * Uses cached metadata when available, falling back to a
     * live daemon query when the cache is absent or {@code null}.
     *
     * @return the host
     */
    String host();

    /**
     * Returns the mapped host port.
     * Uses cached metadata when available. Returns {@code null}
     * if the port is not in the cached bindings from startup inspection.
     *
     * @param containerPort the container port
     * @return the mapped host port, or {@code null} if not mapped
     */
    Integer hostPort(int containerPort);

    /**
     * Copies a file into the container.
     *
     * @param containerPath destination path
     * @param fileName file name
     * @param content file content
     * @param mode file mode
     */
    void copyFileToContainer(String containerPath, String fileName, byte[] content, int mode);

    /**
     * Returns the container spec used to create this container.
     *
     * @return the container spec, never {@code null}
     */
    ContainerSpec spec();

    /**
     * Destroys this container.
     */
    @Override
    void close();
}
