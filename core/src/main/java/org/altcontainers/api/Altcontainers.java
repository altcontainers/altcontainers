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

import java.util.Set;
import java.util.function.Consumer;

/**
 * Entry point for programmatic Altcontainers configuration.
 *
 * <p>Call {@link #configure(Consumer)} once before any container or network
 * is created. Programmatic configuration overrides only the properties that
 * are <em>explicitly set</em> via the builder; all other properties fall
 * through to system properties, environment variables, properties files,
 * and defaults. Pass {@code null} to clear the configuration and fall back
 * to system properties, environment variables, and properties files.
 *
 * <p>Resolution precedence (per property, first wins):
 * <ol>
 *   <li>Programmatic — only if explicitly set via builder setter.</li>
 *   <li>System property ({@code -D} flag).</li>
 *   <li>Environment variable ({@code ALTCONTAINERS_*}).</li>
 *   <li>User-home file ({@code ~/.altcontainers.properties}).</li>
 *   <li>Classpath resource ({@code altcontainers.properties}).</li>
 *   <li>Hardcoded default.</li>
 * </ol>
 */
public final class Altcontainers {

    private static volatile AltcontainersConfiguration configuration;
    private static volatile Set<String> explicitlySet = Set.of();

    private Altcontainers() {
        // Intentionally empty
    }

    /**
     * Configures the Altcontainers runtime.
     *
     * <p>Only properties whose builder setters are called take programmatic
     * precedence. Properties not set in the builder fall through to system
     * properties, environment variables, properties files, and defaults.
     *
     * <p>Pass {@code null} to clear the configuration and fall back to
     * system properties, environment variables, and properties files.
     *
     * @param configurer a consumer that configures a new
     *     {@link AltcontainersConfiguration.Builder}, or {@code null} to clear
     */
    public static void configure(Consumer<AltcontainersConfiguration.Builder> configurer) {
        if (configurer == null) {
            configuration = null;
            explicitlySet = Set.of();
            return;
        }
        AltcontainersConfiguration.Builder builder = AltcontainersConfiguration.builder();
        configurer.accept(builder);
        configuration = builder.build();
        explicitlySet = builder.explicitlySet();
    }

    /**
     * Returns whether the given property key was explicitly set via
     * {@link #configure(Consumer)}.
     *
     * @param key the property key (e.g. "altcontainers.reaper.disabled")
     * @return {@code true} if the property was explicitly configured
     */
    public static boolean isExplicitlySet(String key) {
        return explicitlySet.contains(key);
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
