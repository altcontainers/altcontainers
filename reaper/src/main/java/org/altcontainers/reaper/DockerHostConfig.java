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

package org.altcontainers.reaper;

import com.github.dockerjava.core.DefaultDockerClientConfig;

/** Docker host configuration helpers for the reaper process. */
final class DockerHostConfig {

    static final String ALTCONTAINERS_DOCKER_HOST_PROPERTY = "altcontainers.docker.host";

    private DockerHostConfig() {
        // Intentionally empty
    }

    /**
     * Creates a Docker client config with Altcontainers-specific overrides.
     *
     * @return the Docker client config
     */
    static DefaultDockerClientConfig createDockerClientConfig() {
        return applyAltcontainersDockerHost(DefaultDockerClientConfig.createDefaultConfigBuilder())
                .build();
    }

    /**
     * Applies the {@value #ALTCONTAINERS_DOCKER_HOST_PROPERTY} system property
     * to the given builder, if set.
     *
     * @param builder the docker-java config builder
     * @return the builder with the Altcontainers host applied
     */
    static DefaultDockerClientConfig.Builder applyAltcontainersDockerHost(DefaultDockerClientConfig.Builder builder) {
        String dockerHost = System.getProperty(ALTCONTAINERS_DOCKER_HOST_PROPERTY);
        if (dockerHost == null) {
            return builder;
        }
        if (dockerHost.isBlank()) {
            throw new IllegalArgumentException(ALTCONTAINERS_DOCKER_HOST_PROPERTY + " must not be blank");
        }
        try {
            return builder.withDockerHost(dockerHost);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    ALTCONTAINERS_DOCKER_HOST_PROPERTY + " must be a valid Docker host URI, got '" + dockerHost + "'",
                    e);
        }
    }
}
