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

package nonapi.org.altcontainers.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Narrow container inspect result extracted from a Docker Engine API response.
 *
 * @param running whether the container is in the {@code running} state
 * @param oomKilled whether the container was OOM-killed
 * @param dead whether the container is dead
 * @param exitCode the container exit code, or {@code null}
 * @param error the container error, or {@code null}
 * @param labels the container labels
 * @param ports the container port bindings keyed by container port spec
 */
public record DockerContainerInspect(
        boolean running,
        boolean oomKilled,
        boolean dead,
        Integer exitCode,
        String error,
        Map<String, String> labels,
        Map<String, List<DockerPortBinding>> ports) {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerContainerInspect.class);

    /**
     * Returns the host port for the given container port, or {@code -1} if no mapping exists.
     *
     * @param containerPort the container port
     * @return the host port, or {@code -1}
     */
    public int hostPort(int containerPort) {
        List<DockerPortBinding> bindings = ports.get(containerPort + "/tcp");
        if (bindings == null || bindings.isEmpty()) {
            return -1;
        }
        DockerPortBinding binding = bindings.get(0);
        if (binding == null || binding.hostPort() == null || binding.hostPort().isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(binding.hostPort());
        } catch (NumberFormatException e) {
            LOGGER.warn(
                    "Non-numeric host port '{}' for container port {} in inspect response",
                    binding.hostPort(),
                    containerPort);
            return -1;
        }
    }

    /**
     * Returns a human-readable diagnostics string describing terminal state.
     *
     * @return diagnostics string, or empty
     */
    public String diagnostics() {
        List<String> parts = new ArrayList<>();
        if (oomKilled) {
            parts.add("container was OOMKilled");
        }
        if (dead) {
            parts.add("container is dead");
        }
        if (exitCode != null && exitCode != 0) {
            parts.add("exitCode=" + exitCode);
        }
        if (error != null && !error.isBlank()) {
            parts.add("error=" + error);
        }
        return String.join("; ", parts);
    }
}
