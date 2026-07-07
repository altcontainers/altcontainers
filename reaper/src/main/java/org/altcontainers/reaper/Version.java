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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Provides the current Altcontainers version string loaded from the classpath.
 */
public final class Version {

    /** Fallback version returned when the version resource is missing or unreadable. */
    public static final String UNKNOWN = "UNKNOWN";

    private static final String RESOURCE_NAME = "version.properties";
    private static final String VERSION_PROPERTY = "version";
    private static final String VERSION = loadVersion(Version::getResourceAsStream);

    private Version() {
        // Intentionally empty
    }

    /**
     * Returns the Altcontainers version string.
     *
     * @return the version string, or {@link #UNKNOWN} if the version resource is missing
     */
    public static String version() {
        return VERSION;
    }

    /**
     * Loads the version from {@code version.properties} on the classpath.
     *
     * @param resourceProvider a function that opens a classpath resource by name
     * @return the loaded version string, or {@link #UNKNOWN} if the resource is missing or unreadable
     */
    static String loadVersion(final Function<String, InputStream> resourceProvider) {
        Objects.requireNonNull(resourceProvider, "resourceProvider must not be null");
        final var inputStream = resourceProvider.apply(RESOURCE_NAME);
        if (inputStream == null) {
            return UNKNOWN;
        }
        var properties = new Properties();
        try (inputStream) {
            properties.load(inputStream);
        } catch (IOException e) {
            return UNKNOWN;
        }
        final var version = properties.getProperty(VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
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
