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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for {@link Launcher} command construction and property forwarding.
 */
class LauncherTest {

    private static final int SOCKET_CONNECT_TIMEOUT_MS = 2_000;
    private static final int SOCKET_READ_TIMEOUT_MS = 2_000;
    private static final long CLEANUP_PORT_WAIT_MS = 30_000;

    static boolean reaperJarAvailable() {
        return LauncherTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("altcontainers.reaper.connection.timeout.ms");
    }

    @Test
    void shouldPassResolvedReaperTimeoutsAsExplicitArgs() {
        List<String> cmd = Launcher.buildCommand(
                "session-123", Path.of("reaper.jar"), "java", false, Duration.ofSeconds(12), Duration.ofSeconds(7));
        assertThat(cmd).contains("-Daltcontainers.reaper.session.id=session-123");
        assertThat(cmd).contains("-Daltcontainers.reaper.connection.timeout.ms=12000");
        assertThat(cmd).contains("-Daltcontainers.reaper.stop.timeout.ms=7000");
        assertThat(cmd).contains("-jar");
        assertThat(cmd).contains("reaper.jar");
    }

    @Test
    void shouldNotForwardTimeoutKeysFromSystemProperties() {
        System.setProperty("altcontainers.reaper.connection.timeout.ms", "999");
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.connection.timeout.ms"))
                .isFalse();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.startup.timeout.ms"))
                .isFalse();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.stop.timeout.ms"))
                .isFalse();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.log.directory"))
                .isTrue();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.log.level"))
                .isTrue();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.docker.host"))
                .isTrue();
    }

    @Test
    @Timeout(30)
    @EnabledIf("reaperJarAvailable")
    void launchShouldStartReaperProcessWithNoPreExistingJar() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try {
            Launcher.launch(sessionId);

            Integer port = waitForPort(sessionId, 10_000);
            assertThat(port).as("reaper port should appear").isNotNull();

            completeHandshake(port, sessionId);
        } finally {
            cleanupSession(sessionId, jarPath);
        }
    }

    @Test
    @Timeout(30)
    @EnabledIf("reaperJarAvailable")
    void launchShouldStartReaperProcessWhenIdenticalJarAlreadyExists() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        // Pre-place a byte-identical JAR to trigger the "unchanged" branch
        try (InputStream in = LauncherTest.class.getClassLoader().getResourceAsStream("reaper.jar")) {
            assertThat(in).isNotNull();
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Launcher.launch(sessionId);

            Integer port = waitForPort(sessionId, 10_000);
            assertThat(port)
                    .as("reaper port should appear after relaunch with unchanged JAR")
                    .isNotNull();

            completeHandshake(port, sessionId);
        } finally {
            cleanupSession(sessionId, jarPath);
        }
    }

    private static void completeHandshake(int port, String sessionId) throws IOException {
        try (Socket socket = connectToReaper(port);
                BufferedWriter writer =
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer.write(sessionId + "\n");
            writer.flush();
            assertThat(reader.readLine()).isEqualTo("OK");
            writer.write("TERMINATE\n");
            writer.flush();
        }
    }

    private static Integer waitForPort(String sessionId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Integer port = null;
        while (System.currentTimeMillis() < deadline && port == null) {
            port = ReaperDiscovery.readPort(sessionId).orElse(null);
            if (port == null) {
                Thread.sleep(100);
            }
        }
        return port;
    }

    private static void cleanupSession(String sessionId, Path jarPath) {
        // Kill any lingering reaper processes for this session
        try {
            Integer port = waitForPort(sessionId, CLEANUP_PORT_WAIT_MS);
            if (port != null) {
                try (Socket socket = connectToReaper(port);
                        BufferedWriter writer = new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    writer.write(sessionId + "\n");
                    writer.flush();
                    writer.write("TERMINATE\n");
                    writer.flush();
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
        try {
            Files.deleteIfExists(jarPath);
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(ReaperDiscovery.portFilePath(sessionId));
        } catch (IOException ignored) {
        }
    }

    private static Socket connectToReaper(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", port), SOCKET_CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        return socket;
    }
}
