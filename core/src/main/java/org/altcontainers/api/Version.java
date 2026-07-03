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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the current Altcontainers version string loaded from the classpath.
 *
 * <p>Version information is loaded once during class initialization from the classpath resource
 * {@code containers-version.properties}. If the resource is missing or unreadable, the version falls back to
 * {@link #UNKNOWN}. This class cannot be instantiated.
 */
public final class Version {

    /**
     * Fallback version returned when the version resource is missing or unreadable.
     * The value is {@code "UNKNOWN"}.
     */
    public static final String UNKNOWN = "UNKNOWN";

    private static final String RESOURCE_NAME = "containers-version.properties";

    private static final String VERSION_PROPERTY = "altcontainers.core.version";

    private static final Logger LOGGER = LoggerFactory.getLogger(Version.class);

    private static final String VERSION = loadVersion(Version::getResourceAsStream);

    private Version() {
        // Intentionally empty
    }

    /**
     * Returns the Altcontainers version string.
     *
     * @return the version string, or {@link #UNKNOWN} if the version resource is missing or unreadable
     */
    public static String version() {
        return VERSION;
    }

    /**
     * Loads the version string from the supplied resource provider.
     *
     * <p>This method reads the {@code altcontainers.core.version} property from the
     * {@code containers-version.properties} resource obtained via the supplied provider.
     * Returns {@link #UNKNOWN} when the resource is absent, unreadable, or missing the
     * {@code altcontainers.core.version} property.
     *
     * @param resourceProvider the function that supplies an {@link InputStream} for a resource name
     * @return the version string, or {@link #UNKNOWN} on any loading failure
     * @throws NullPointerException if {@code resourceProvider} is {@code null}
     */
    static String loadVersion(final Function<String, InputStream> resourceProvider) {
        Objects.requireNonNull(resourceProvider, "resourceProvider must not be null");
        final var inputStream = resourceProvider.apply(RESOURCE_NAME);
        if (inputStream == null) {
            LOGGER.warn("Version resource '{}' not found; version will be '{}'", RESOURCE_NAME, UNKNOWN);
            return UNKNOWN;
        }
        var properties = new Properties();
        try (inputStream) {
            properties.load(inputStream);
        } catch (IOException e) {
            LOGGER.warn(
                    "Failed to load version resource '{}': {}; version will be '{}'",
                    RESOURCE_NAME,
                    e.getMessage(),
                    UNKNOWN);
            return UNKNOWN;
        }
        final var version = properties.getProperty(VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
            LOGGER.warn(
                    "Version property '{}' missing or blank in '{}'; version will be '{}'",
                    VERSION_PROPERTY,
                    RESOURCE_NAME,
                    UNKNOWN);
            return UNKNOWN;
        }
        return version;
    }

    private static InputStream getResourceAsStream(String name) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            InputStream is = ctx.getResourceAsStream(name);
            if (is != null) {
                return is;
            }
        }
        ClassLoader def = Version.class.getClassLoader();
        if (def != null) {
            InputStream is = def.getResourceAsStream(name);
            if (is != null) {
                return is;
            }
        }
        return ClassLoader.getSystemResourceAsStream(name);
    }
}
