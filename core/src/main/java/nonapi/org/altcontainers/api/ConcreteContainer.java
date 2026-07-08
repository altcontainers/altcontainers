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

package nonapi.org.altcontainers.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;

/**
 * Concrete implementation of {@link Container} backed by a real Docker container.
 *
 * <p>This class resides in the {@code nonapi} package because direct construction
 * is unsupported. Use {@link Container#create(ContainerSpec)} to provision a
 * container through the public API.
 */
public class ConcreteContainer implements Container {

    private final String id;
    private final String image;
    private final ContainerSpec spec;
    private final ContainerMetadata metadata;
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);

    /**
     * Creates a concrete container handle.
     *
     * @param id the Docker-assigned container id
     * @param image the Docker image name
     * @param spec the container spec used to create this container
     * @param metadata post-start metadata, or {@code null}
     */
    public ConcreteContainer(String id, String image, ContainerSpec spec, ContainerMetadata metadata) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.image = Objects.requireNonNull(image, "image must not be null");
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.metadata = metadata;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String image() {
        return image;
    }

    @Override
    public boolean isRunning() {
        if (metadata != null && metadata.running()) {
            return true;
        }
        return ContainerManager.getInstance().isContainerRunning(id);
    }

    @Override
    public String host() {
        if (metadata != null && metadata.host() != null) {
            return metadata.host();
        }
        return ContainerManager.getInstance().host();
    }

    @Override
    public Integer hostPort(int containerPort) {
        if (metadata != null) {
            Integer cached = metadata.portBindings().get(containerPort);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    @Override
    public void copyFileToContainer(String containerPath, String fileName, byte[] content, int mode) {
        ContainerManager.getInstance().putArchive(id, containerPath, fileName, content, mode);
    }

    @Override
    public ContainerSpec spec() {
        return spec;
    }

    @Override
    public void close() {
        if (closeStarted.compareAndSet(false, true)) {
            ContainerManager.getInstance().closeContainer(this);
        }
    }
}
