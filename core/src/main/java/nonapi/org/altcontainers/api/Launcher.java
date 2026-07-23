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

package nonapi.org.altcontainers.api;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the reaper as a detached process.
 */
public final class Launcher {

    private static final Logger logger = LoggerFactory.getLogger(Launcher.class);
    private static final String ALTCONTAINERS_DOCKER_HOST_PROPERTY = "altcontainers.docker.host";

    private Launcher() {
        // Intentionally empty
    }

    /**
     * Returns whether the current operating system is Linux.
     *
     * @return {@code true} if the OS name contains "linux"
     */
    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /**
     * Extracts and launches the reaper JAR.
     *
     * @param sessionId the session UUID
     */
    public static void launch(String sessionId) {
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try {
            Files.createDirectories(jarPath.getParent());
            try (java.io.InputStream in = Launcher.class.getClassLoader().getResourceAsStream("reaper.jar")) {
                if (in == null) {
                    logger.error("reaper.jar not found on classpath");
                    return;
                }
                Path tmp = jarPath.resolveSibling(jarPath.getFileName() + ".tmp");
                try {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    boolean unchanged = Files.exists(jarPath) && Files.mismatch(jarPath, tmp) == -1L;
                    if (!unchanged) {
                        try {
                            Files.move(
                                    tmp, jarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException e) {
                            logger.debug("Atomic move not supported, falling back to non-atomic move");
                            Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to extract reaper JAR: {}", e.getMessage());
        }

        if (!Files.exists(jarPath)) {
            return;
        }

        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = buildCommand(sessionId, jarPath, java, true);

        try {
            new ProcessBuilder(cmd)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            logger.error("Failed to launch reaper: {}", e.getMessage());
        }
    }

    /**
     * Builds the command line for launching the reaper JAR.
     *
     * @param sessionId the session UUID
     * @param jarPath the path to the reaper JAR
     * @param javaCommand the {@code java} command to use
     * @param detached whether to detach via setsid/nohup
     * @return the command line arguments
     */
    static List<String> buildCommand(String sessionId, Path jarPath, String javaCommand, boolean detached) {
        AltcontainersProperties properties = AltcontainersProperties.instance();
        return buildCommand(
                sessionId,
                jarPath,
                javaCommand,
                detached,
                properties.reaperConnectionTimeout(),
                properties.reaperStopTimeout());
    }

    /**
     * Builds the command line for launching the reaper JAR with explicit
     * resolved reaper timeouts.
     *
     * @param sessionId the session UUID
     * @param jarPath the path to the reaper JAR
     * @param javaCommand the {@code java} command to use
     * @param detached whether to detach via setsid/nohup
     * @param reaperConnectionTimeout the resolved reaper connection timeout
     * @param reaperStopTimeout the resolved reaper stop timeout
     * @return the command line arguments
     */
    static List<String> buildCommand(
            String sessionId,
            Path jarPath,
            String javaCommand,
            boolean detached,
            Duration reaperConnectionTimeout,
            Duration reaperStopTimeout) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(jarPath, "jarPath must not be null");
        Objects.requireNonNull(javaCommand, "javaCommand must not be null");
        Objects.requireNonNull(reaperConnectionTimeout, "reaperConnectionTimeout must not be null");
        Objects.requireNonNull(reaperStopTimeout, "reaperStopTimeout must not be null");
        List<String> cmd = new ArrayList<>();
        if (detached) {
            if (isLinux()) {
                cmd.add("setsid");
            } else {
                cmd.add("nohup");
            }
        }
        cmd.add(javaCommand);
        cmd.add("-Daltcontainers.reaper.session.id=" + sessionId);
        cmd.add("-Daltcontainers.reaper.connection.timeout.ms=" + reaperConnectionTimeout.toMillis());
        cmd.add("-Daltcontainers.reaper.stop.timeout.ms=" + reaperStopTimeout.toMillis());
        for (Map.Entry<Object, Object> e : new TreeMap<>(System.getProperties()).entrySet()) {
            String key = String.valueOf(e.getKey());
            if (shouldForwardSystemProperty(key)) {
                cmd.add("-D" + key + "=" + e.getValue());
            }
        }
        cmd.add("-jar");
        cmd.add(jarPath.toString());
        return cmd;
    }

    /**
     * Returns whether the given system property key should be forwarded
     * to the reaper process.
     *
     * @param key the system property key
     * @return {@code true} if the property should be forwarded
     */
    static boolean shouldForwardSystemProperty(String key) {
        if ("altcontainers.reaper.session.id".equals(key)) {
            return false;
        }
        if (isReaperTimeoutKey(key)) {
            return false;
        }
        return key.startsWith("altcontainers.reaper.") || ALTCONTAINERS_DOCKER_HOST_PROPERTY.equals(key);
    }

    private static boolean isReaperTimeoutKey(String key) {
        return "altcontainers.reaper.connection.timeout.ms".equals(key)
                || "altcontainers.reaper.startup.timeout.ms".equals(key)
                || "altcontainers.reaper.stop.timeout.ms".equals(key);
    }
}
