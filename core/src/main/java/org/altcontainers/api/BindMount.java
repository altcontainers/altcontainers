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
 * A single host-to-container bind mount.
 *
 * <p>An immutable value object carrying the two absolute paths that define one bind mount and whether the
 * container may write through it. Instances are created via
 * {@link GenericContainerSpec.Builder#bindDirectory(String, String)} or
 * {@link GenericContainerSpec.Builder#bindDirectory(String, String, boolean)} and consumed when building a
 * container.
 *
 * <p>A bind mount shares the host path with the container directly; no content is copied. The host path is
 * resolved by the Docker daemon, so it must exist on (or be shared with) the daemon host.
 *
 * @param hostPath the absolute host path; must not be {@code null} or blank
 * @param containerPath the absolute in-container path; must not be {@code null} or blank
 * @param readOnly {@code true} to mount read-only, {@code false} to mount read-write
 */
public record BindMount(String hostPath, String containerPath, boolean readOnly) {

    /**
     * Compact canonical constructor that validates host and container paths.
     *
     * @param hostPath the absolute host path; must not be {@code null} or blank
     * @param containerPath the absolute in-container path; must not be {@code null} or blank
     * @param readOnly {@code true} to mount read-only, {@code false} to mount read-write
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

    /**
     * Creates a read-write bind mount.
     *
     * @param hostPath the absolute host path; must not be {@code null} or blank
     * @param containerPath the absolute in-container path; must not be {@code null} or blank
     * @throws IllegalArgumentException if either path is {@code null} or blank
     */
    public BindMount(String hostPath, String containerPath) {
        this(hostPath, containerPath, false);
    }
}
