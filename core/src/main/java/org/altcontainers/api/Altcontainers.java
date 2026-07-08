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

import java.util.function.Consumer;

/**
 * Entry point for programmatic Altcontainers configuration.
 *
 * <p>Call {@link #configure(Consumer)} once before any container or network
 * is created. Programmatic configuration takes precedence over environment
 * variables and properties files. Pass {@code null} to clear the configuration
 * and fall back to environment variables and properties files.
 */
public final class Altcontainers {

    private static volatile AltcontainersConfiguration configuration;

    private Altcontainers() {
        // Intentionally empty
    }

    /**
     * Configures the Altcontainers runtime.
     *
     * <p>Pass {@code null} to clear the configuration and fall back to
     * environment variables and properties files.
     *
     * @param configurer a consumer that configures a new
     *     {@link AltcontainersConfiguration.Builder}, or {@code null} to clear
     */
    public static void configure(Consumer<AltcontainersConfiguration.Builder> configurer) {
        if (configurer == null) {
            configuration = null;
            return;
        }
        AltcontainersConfiguration.Builder builder = AltcontainersConfiguration.builder();
        configurer.accept(builder);
        configuration = builder.build();
    }

    /**
     * Returns the programmatic configuration, or {@code null} if none has
     * been set.
     *
     * @return the configuration, or {@code null}
     */
    public static AltcontainersConfiguration configuration() {
        return configuration;
    }
}
