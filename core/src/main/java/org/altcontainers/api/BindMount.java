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

/**
 * A single host-to-container read-write bind mount.
 *
 * <p>An immutable value object carrying the two absolute paths that define one bind mount. Instances are
 * created via {@link org.altcontainers.api.ContainerSpec.Builder#bindDirectory(String, String)} and
 * consumed when building a container.
 *
 * @param hostPath the absolute host path; must not be {@code null} or blank
 * @param containerPath the absolute in-container path; must not be {@code null} or blank
 */
public record BindMount(String hostPath, String containerPath) {

    /**
     * Compact canonical constructor that validates host and container paths.
     *
     * @param hostPath the absolute host path; must not be {@code null} or blank
     * @param containerPath the absolute in-container path; must not be {@code null} or blank
     * @throws IllegalArgumentException if either path is {@code null} or blank
     */
    public BindMount {
        if (hostPath == null || hostPath.isBlank()) {
            throw new IllegalArgumentException("hostPath must not be blank");
        }
        if (containerPath == null || containerPath.isBlank()) {
            throw new IllegalArgumentException("containerPath must not be blank");
        }
    }
}
