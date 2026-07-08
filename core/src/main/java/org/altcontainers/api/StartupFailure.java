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
 * Immutable context passed to failed-startup-attempt lifecycle callbacks.
 *
 * @param container the doomed per-attempt container handle
 * @param attempt the 1-based startup attempt number
 * @param maxAttempts the configured maximum startup attempts
 * @param failure the failure that caused the attempt to fail
 */
public record StartupFailure(Container container, int attempt, int maxAttempts, Throwable failure) {

    /**
     * Creates a startup failure context.
     *
     * @param container the doomed per-attempt container handle; must not be
     *     {@code null}
     * @param attempt the 1-based startup attempt number
     * @param maxAttempts the configured maximum startup attempts
     * @param failure the failure that caused the attempt to fail; must not be
     *     {@code null}
     * @throws NullPointerException if {@code container} or {@code failure} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code attempt} is outside
     *     {@code 1..maxAttempts}
     */
    public StartupFailure {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(failure, "failure");
        if (attempt < 1 || attempt > maxAttempts) {
            throw new IllegalArgumentException("attempt must be in 1..maxAttempts");
        }
    }
}
