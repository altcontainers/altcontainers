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

package nonapi.org.altcontainers.reaper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.altcontainers.api.ContainerException;

/**
 * Internal, immutable configuration for the Altcontainers resource reaper daemon.
 *
 * <p>Values are resolved with the following precedence (highest first):
 *
 * <ol>
 *   <li>JVM system properties (those beginning with {@code altcontainers.reaper.});
 *   <li>classpath {@code altcontainers.properties};
 *   <li>built-in defaults.
 * </ol>
 *
 * <p>No environment-variable configuration is supported. Invalid values fail fast with
 * {@link ContainerException} at construction time.
 *
 * @param disabled whether the daemon launch is disabled (best-effort shutdown-hook cleanup only)
 * @param connectionTimeoutMilliseconds deadline for daemon startup and TCP connection timeout
 * @param cleanupTimeoutMilliseconds deadline for session cleanup operations
 * @param heartbeatIntervalMilliseconds interval between HEARTBEAT commands sent by the client
 * @param sessionTimeoutMilliseconds how long the reaper waits after the last heartbeat before cleanup
 * @param idleTimeoutMilliseconds idle timeout before the reaper self-terminates (0 = disabled)
 * @param logLevel log level: {@code OFF}, {@code ERROR}, {@code INFO}, or {@code TRACE}
 * @param daemonLogDirectory directory for daemon log files
 */
public record Configuration(
        boolean disabled,
        long connectionTimeoutMilliseconds,
        long cleanupTimeoutMilliseconds,
        long heartbeatIntervalMilliseconds,
        long sessionTimeoutMilliseconds,
        long idleTimeoutMilliseconds,
        String logLevel,
        String daemonLogDirectory) {

    private static final boolean DEFAULT_DISABLED = false;

    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L;

    private static final long DEFAULT_CLEANUP_TIMEOUT_MS = 30_000L;

    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L;

    private static final long DEFAULT_SESSION_TIMEOUT_MS = 30_000L;

    private static final long DEFAULT_IDLE_TIMEOUT_MS = 180_000L;

    private static final String DEFAULT_LOG_LEVEL = "INFO";

    private static final String DEFAULT_DAEMON_LOG_DIR = System.getProperty("java.io.tmpdir");

    private static final Set<String> VALID_LOG_LEVELS = Set.of("OFF", "ERROR", "INFO", "TRACE");

    private static final String PROPERTIES_RESOURCE = "altcontainers.properties";

    private static final String PREFIX = "altcontainers.reaper.";

    private static final String KEY_DISABLED = PREFIX + "disabled";

    private static final String KEY_CONNECTION_TIMEOUT = PREFIX + "connection.timeout.milliseconds";

    private static final String KEY_CLEANUP_TIMEOUT = PREFIX + "cleanup.timeout.milliseconds";

    private static final String KEY_LOG_LEVEL = PREFIX + "log.level";

    private static final String KEY_HEARTBEAT_INTERVAL = PREFIX + "heartbeat.interval.milliseconds";

    private static final String KEY_SESSION_TIMEOUT = PREFIX + "session.timeout.milliseconds";

    private static final String KEY_IDLE_TIMEOUT = PREFIX + "idle.timeout.milliseconds";

    private static final String KEY_DAEMON_LOG_DIR = PREFIX + "log.directory";

    /**
     * Compact canonical constructor that validates the resolved fields.
     *
     * @param disabled whether the daemon launch is disabled
     * @param connectionTimeoutMilliseconds deadline for daemon startup and TCP connection timeout
     * @param cleanupTimeoutMilliseconds deadline for session cleanup operations
     * @param heartbeatIntervalMilliseconds interval between HEARTBEAT commands sent by the client
     * @param sessionTimeoutMilliseconds how long the reaper waits after the last heartbeat before cleanup
     * @param idleTimeoutMilliseconds idle timeout before the reaper self-terminates (0 = disabled)
     * @param logLevel log level: {@code OFF}, {@code ERROR}, {@code INFO}, or {@code TRACE}
     * @param daemonLogDirectory directory for daemon log files
     * @throws ContainerException if any field violates its validation invariant
     */
    public Configuration {
        if (connectionTimeoutMilliseconds <= 0) {
            throw new ContainerException(
                    KEY_CONNECTION_TIMEOUT + " must be positive, was " + connectionTimeoutMilliseconds);
        }
        if (cleanupTimeoutMilliseconds <= 0) {
            throw new ContainerException(KEY_CLEANUP_TIMEOUT + " must be positive, was " + cleanupTimeoutMilliseconds);
        }
        if (heartbeatIntervalMilliseconds <= 0) {
            throw new ContainerException(
                    KEY_HEARTBEAT_INTERVAL + " must be positive, was " + heartbeatIntervalMilliseconds);
        }
        if (sessionTimeoutMilliseconds <= 0) {
            throw new ContainerException(KEY_SESSION_TIMEOUT + " must be positive, was " + sessionTimeoutMilliseconds);
        }
        if (sessionTimeoutMilliseconds <= heartbeatIntervalMilliseconds) {
            throw new ContainerException(KEY_SESSION_TIMEOUT + " (" + sessionTimeoutMilliseconds
                    + ") must be greater than " + KEY_HEARTBEAT_INTERVAL
                    + " (" + heartbeatIntervalMilliseconds + ")");
        }
        if (idleTimeoutMilliseconds < 0) {
            throw new ContainerException(KEY_IDLE_TIMEOUT + " must be >= 0, was " + idleTimeoutMilliseconds);
        }
        if (logLevel == null || !VALID_LOG_LEVELS.contains(logLevel.toUpperCase())) {
            throw new ContainerException(KEY_LOG_LEVEL + " must be one of " + VALID_LOG_LEVELS + ", was " + logLevel);
        }
        if (daemonLogDirectory == null || daemonLogDirectory.isBlank()) {
            throw new ContainerException(KEY_DAEMON_LOG_DIR + " must not be blank");
        }
        logLevel = logLevel.toUpperCase();
    }

    /**
     * Loads a configuration using the thread context ClassLoader, then the defining ClassLoader.
     *
     * @return a validated reaper configuration
     * @throws ContainerException if any resolved value is invalid
     */
    public static Configuration load() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            return load(contextClassLoader);
        }
        return load(Configuration.class.getClassLoader());
    }

    /**
     * Loads a configuration using the given ClassLoader for classpath properties.
     *
     * @param classLoader the ClassLoader used to read {@code altcontainers.properties}
     * @return a validated reaper configuration
     * @throws ContainerException if any resolved value is invalid
     */
    public static Configuration load(ClassLoader classLoader) {
        Map<String, String> base = loadClasspathProperties(classLoader);
        Map<String, String> merged = overlaySystemProperties(base);
        return from(merged);
    }

    /**
     * Builds a configuration from a resolved property map, applying built-in defaults.
     *
     * @param properties the merged property map keyed by full property name
     * @return a validated reaper configuration
     * @throws ContainerException if any resolved value is invalid
     */
    static Configuration from(Map<String, String> properties) {
        boolean disabled = parseBoolean(properties, KEY_DISABLED, DEFAULT_DISABLED);
        long connectionTimeoutMs = parseLong(properties, KEY_CONNECTION_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT_MS);
        long cleanupTimeoutMs = parseLong(properties, KEY_CLEANUP_TIMEOUT, DEFAULT_CLEANUP_TIMEOUT_MS);
        long heartbeatIntervalMs = parseLong(properties, KEY_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL_MS);
        long sessionTimeoutMs = parseLong(properties, KEY_SESSION_TIMEOUT, DEFAULT_SESSION_TIMEOUT_MS);
        long idleTimeoutMs = parseLong(properties, KEY_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT_MS);
        String logLevel = properties.getOrDefault(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL);
        String daemonLogDir = properties.getOrDefault(KEY_DAEMON_LOG_DIR, DEFAULT_DAEMON_LOG_DIR);
        return new Configuration(
                disabled,
                connectionTimeoutMs,
                cleanupTimeoutMs,
                heartbeatIntervalMs,
                sessionTimeoutMs,
                idleTimeoutMs,
                logLevel,
                daemonLogDir);
    }

    private static Map<String, String> loadClasspathProperties(ClassLoader classLoader) {
        Map<String, String> map = new HashMap<>();
        ClassLoader loader = classLoader != null ? classLoader : Configuration.class.getClassLoader();
        InputStream stream = openResource(loader, PROPERTIES_RESOURCE);
        if (stream == null) {
            return map;
        }
        Properties properties = new Properties();
        try (stream) {
            properties.load(stream);
        } catch (IOException e) {
            return map;
        }
        for (String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    private static InputStream openResource(ClassLoader loader, String name) {
        if (loader != null) {
            InputStream stream = loader.getResourceAsStream(name);
            if (stream != null) {
                return stream;
            }
        }
        ClassLoader defining = Configuration.class.getClassLoader();
        if (defining != null) {
            return defining.getResourceAsStream(name);
        }
        return ClassLoader.getSystemResourceAsStream(name);
    }

    private static Map<String, String> overlaySystemProperties(Map<String, String> base) {
        Map<String, String> merged = new HashMap<>(base);
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith(PREFIX)) {
                merged.put(key, String.valueOf(entry.getValue()));
            }
        }
        return merged;
    }

    private static long parseLong(Map<String, String> properties, String key, long defaultValue) {
        String raw = properties.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ContainerException(key + " must be a valid long, was " + raw);
        }
    }

    private static boolean parseBoolean(Map<String, String> properties, String key, boolean defaultValue) {
        String raw = properties.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }
}
