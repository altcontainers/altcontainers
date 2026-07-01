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

/**
 * Immutable description of a Linux resource limit (ulimit).
 *
 * <p>{@code Ulimit} captures a single ulimit entry with a name, soft limit, and hard limit.
 * Ulimits are OCI-standard and apply identically across Docker, Podman, and other
 * OCI-compatible container runtimes.
 *
 * <p>Common ulimit names include:
 *
 * <ul>
 *   <li>{@code "nofile"} — maximum number of open file descriptors
 *   <li>{@code "nproc"} — maximum number of processes
 *   <li>{@code "memlock"} — maximum locked memory
 * </ul>
 *
 * <pre>{@code
 * Ulimit ulimit = new Ulimit("nofile", 65536, 65536);
 * ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
 *         .ulimit(ulimit.name(), ulimit.soft(), ulimit.hard())
 *         .build();
 * }</pre>
 *
 * @param name the ulimit name (e.g., {@code "nofile"}); must not be {@code null} or blank
 * @param soft the soft limit; must be {@code >= 0}
 * @param hard the hard limit; must be {@code >= 0}
 */
public record Ulimit(String name, long soft, long hard) {

    /**
     * Compact canonical constructor that validates all fields.
     *
     * @param name the ulimit name (e.g., {@code "nofile"}); must not be {@code null} or blank
     * @param soft the soft limit; must be {@code >= 0}
     * @param hard the hard limit; must be {@code >= 0}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, if {@code soft} or {@code hard} is
     *     negative, or if {@code soft > hard} when {@code hard > 0}
     */
    public Ulimit {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (soft < 0) {
            throw new IllegalArgumentException("soft must be >= 0, was " + soft);
        }
        if (hard < 0) {
            throw new IllegalArgumentException("hard must be >= 0, was " + hard);
        }
        if (hard > 0 && soft > hard) {
            throw new IllegalArgumentException(
                    "soft must be <= hard when hard > 0, was soft=" + soft + ", hard=" + hard);
        }
    }
}
