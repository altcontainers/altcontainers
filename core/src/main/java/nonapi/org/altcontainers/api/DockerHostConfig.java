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

import com.github.dockerjava.core.DefaultDockerClientConfig;
import java.net.URI;
import java.util.Locale;
import org.altcontainers.api.ContainerException;

/** Docker host configuration helpers for core Docker clients. */
final class DockerHostConfig {

    /** Altcontainers system property used to override Docker host selection. */
    static final String ALTCONTAINERS_DOCKER_HOST_PROPERTY = "altcontainers.docker.host";

    private DockerHostConfig() {
        // Intentionally empty
    }

    /**
     * Builds a docker-java config with Altcontainers-specific Docker host
     * overrides applied.
     *
     * @return the Docker client config
     */
    static DefaultDockerClientConfig createDockerClientConfig() {
        return applyAltcontainersDockerHost(DefaultDockerClientConfig.createDefaultConfigBuilder())
                .build();
    }

    /**
     * Applies the Altcontainers Docker host property, if present, to the
     * given builder.
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
            throw new ContainerException(ALTCONTAINERS_DOCKER_HOST_PROPERTY + " must not be blank");
        }
        try {
            return builder.withDockerHost(dockerHost);
        } catch (RuntimeException e) {
            throw new ContainerException(
                    ALTCONTAINERS_DOCKER_HOST_PROPERTY + " must be a valid Docker host URI, got '" + dockerHost + "'",
                    e);
        }
    }

    /**
     * Returns the externally reachable host for published container ports.
     * Unix and named pipe schemes resolve to {@code "localhost"}.
     *
     * @param config the Docker client config
     * @return the host reachable by clients
     * @throws ContainerException if the Docker host URI has no host component
     */
    static String containerHost(DefaultDockerClientConfig config) {
        URI dockerHost = config.getDockerHost();
        String scheme = dockerHost.getScheme();
        String normalizedScheme = scheme != null ? scheme.toLowerCase(Locale.ROOT) : "";
        if ("unix".equals(normalizedScheme) || "npipe".equals(normalizedScheme)) {
            return "localhost";
        }
        String host = dockerHost.getHost();
        if (host == null || host.isBlank()) {
            throw new ContainerException("Docker host URI must include a host: " + dockerHost);
        }
        return host;
    }
}
