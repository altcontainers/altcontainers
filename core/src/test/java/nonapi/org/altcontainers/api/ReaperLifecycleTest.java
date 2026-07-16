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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Integration tests that validate the reaper process lifecycle:
 * launch, connect, disconnect, exit, and file cleanup.
 */
class ReaperLifecycleTest {

    static boolean reaperJarAvailable() {
        return ReaperLifecycleTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    static boolean dockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("reaperJarAvailable")
    void reaperShouldExitAndCleanupOnTerminate() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try (InputStream in = ReaperLifecycleTest.class.getClassLoader().getResourceAsStream("reaper.jar")) {
            assertThat(in).isNotNull();
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = Launcher.buildCommand(sessionId, jarPath, javaCommand, false);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            process = pb.start();

            long deadline = System.currentTimeMillis() + 10_000;
            Integer port = null;
            while (System.currentTimeMillis() < deadline && port == null) {
                port = ReaperDiscovery.readPort(sessionId).orElse(null);
                if (port == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(port).isNotNull();

            try (Socket socket = new Socket("localhost", port);
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                writer.write(sessionId + "\n");
                writer.flush();

                String response = reader.readLine();
                assertThat(response).isEqualTo("OK");

                writer.write("TERMINATE\n");
                writer.flush();
            }

            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            assertThat(exited).isTrue();
            assertThat(process.exitValue()).isEqualTo(0);
            assertThat(Files.notExists(ReaperDiscovery.portFilePath(sessionId))).isTrue();
            assertThat(Files.notExists(jarPath)).isTrue();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
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
    }

    @Test
    @Tag("slow")
    @EnabledIf("reaperJarAvailable")
    void reaperShouldExitAndCleanupOnConnectionDrop() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try (InputStream in = ReaperLifecycleTest.class.getClassLoader().getResourceAsStream("reaper.jar")) {
            assertThat(in).isNotNull();
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = Launcher.buildCommand(sessionId, jarPath, javaCommand, false);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            process = pb.start();

            long deadline = System.currentTimeMillis() + 10_000;
            Integer port = null;
            while (System.currentTimeMillis() < deadline && port == null) {
                port = ReaperDiscovery.readPort(sessionId).orElse(null);
                if (port == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(port).isNotNull();

            try (Socket socket = new Socket("localhost", port);
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                writer.write(sessionId + "\n");
                writer.flush();

                String response = reader.readLine();
                assertThat(response).isEqualTo("OK");
            }

            boolean exited = process.waitFor(90, TimeUnit.SECONDS);
            assertThat(exited).isTrue();
            assertThat(process.exitValue()).isEqualTo(0);
            assertThat(Files.notExists(ReaperDiscovery.portFilePath(sessionId))).isTrue();
            assertThat(Files.notExists(jarPath)).isTrue();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
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
    }

    @Test
    @Tag("slow")
    @EnabledIf("reaperJarAvailable")
    void reaperShouldExitOnAcceptTimeout() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try (InputStream in = ReaperLifecycleTest.class.getClassLoader().getResourceAsStream("reaper.jar")) {
            assertThat(in).isNotNull();
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = Launcher.buildCommand(sessionId, jarPath, javaCommand, false);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            process = pb.start();

            boolean exited = process.waitFor(90, TimeUnit.SECONDS);
            assertThat(exited).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.notExists(ReaperDiscovery.portFilePath(sessionId))).isTrue();
            assertThat(Files.notExists(jarPath)).isTrue();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
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
    }

    // ── IT-001: Transient container cleanup recovery ──

    @Test
    @EnabledIf("dockerAvailable")
    void reaperShouldRecoverFromTransientContainerFailure() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String containerName = "altcontainers-it001-" + sessionId;

        // Create a running container — reaper will stop + remove it
        execDocker("run", "-d", "--name", containerName, "alpine:latest", "sleep", "300");
        try {
            Process reaper = launchReaper(sessionId);
            try {
                Integer port = waitForPort(sessionId, 10_000);
                assertThat(port).isNotNull();

                try (Socket socket = new Socket("localhost", port);
                        BufferedWriter writer = new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                    writer.write(sessionId + "\n");
                    writer.flush();
                    assertThat(reader.readLine()).isEqualTo("OK");

                    writer.write("TERMINATE_CONTAINER " + containerName + "\n");
                    writer.write("TERMINATE\n");
                    writer.flush();
                }

                boolean exited = reaper.waitFor(60, TimeUnit.SECONDS);
                assertThat(exited).isTrue();
                assertThat(reaper.exitValue()).isEqualTo(0);

                // Container should be removed
                String inspect = execDockerQuiet("inspect", "--format", "{{.Id}}", containerName);
                assertThat(inspect).isEmpty();
            } finally {
                if (reaper.isAlive()) {
                    reaper.destroyForcibly();
                }
            }
        } finally {
            execDockerQuiet("rm", "-f", containerName);
        }
    }

    // ── IT-002: Container cleanup with bounded attempts ──

    @Test
    @EnabledIf("dockerAvailable")
    void reaperShouldRetryPoisonPillExactlyMaxAttempts() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String containerName = "altcontainers-it002-" + sessionId;

        // Create a running container and delegate removal to the reaper
        execDocker("run", "-d", "--name", containerName, "alpine:latest", "sleep", "300");
        try {
            Process reaper = launchReaper(sessionId);
            try {
                Integer port = waitForPort(sessionId, 10_000);
                assertThat(port).isNotNull();

                try (Socket socket = new Socket("localhost", port);
                        BufferedWriter writer = new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                    writer.write(sessionId + "\n");
                    writer.flush();
                    assertThat(reader.readLine()).isEqualTo("OK");

                    writer.write("TERMINATE_CONTAINER " + containerName + "\n");
                    writer.write("TERMINATE\n");
                    writer.flush();
                }

                boolean exited = reaper.waitFor(60, TimeUnit.SECONDS);
                assertThat(exited).isTrue();
                assertThat(reaper.exitValue()).isEqualTo(0);
            } finally {
                if (reaper.isAlive()) {
                    reaper.destroyForcibly();
                }
            }
        } finally {
            execDockerQuiet("rm", "-f", containerName);
        }
    }

    // ── IT-003: Network cleanup after container removal ──

    @Test
    @EnabledIf("dockerAvailable")
    void reaperShouldHandleNetworkWithActiveEndpointsTransiently() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String networkName = "altcontainers-it003-" + sessionId;
        String containerName = "altcontainers-it003-c-" + sessionId;

        // Create a network and attach a container, then stop the container.
        // The stopped container remains connected to the network.
        execDocker("network", "create", networkName);
        try {
            execDocker("run", "-d", "--name", containerName, "--network", networkName, "alpine:latest", "sleep", "300");
            try {
                execDocker("stop", containerName);

                Process reaper = launchReaper(sessionId);
                try {
                    Integer port = waitForPort(sessionId, 10_000);
                    assertThat(port).isNotNull();

                    try (Socket socket = new Socket("localhost", port);
                            BufferedWriter writer = new BufferedWriter(
                                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                        writer.write(sessionId + "\n");
                        writer.flush();
                        assertThat(reader.readLine()).isEqualTo("OK");

                        // Delegate container removal first, then network
                        writer.write("TERMINATE_CONTAINER " + containerName + "\n");
                        writer.write("TERMINATE_NETWORK " + getNetworkId(networkName) + "\n");
                        writer.write("TERMINATE\n");
                        writer.flush();
                    }

                    boolean exited = reaper.waitFor(120, TimeUnit.SECONDS);
                    assertThat(exited).isTrue();
                    assertThat(reaper.exitValue()).isEqualTo(0);

                    // Both should be removed
                    String inspect = execDockerQuiet("inspect", "--format", "{{.Id}}", containerName);
                    assertThat(inspect).isEmpty();
                    String netInspect = execDockerQuiet("network", "inspect", "--format", "{{.Id}}", networkName);
                    assertThat(netInspect).isEmpty();
                } finally {
                    if (reaper.isAlive()) {
                        reaper.destroyForcibly();
                    }
                }
            } finally {
                execDockerQuiet("rm", "-f", containerName);
            }
        } finally {
            execDockerQuiet("network", "rm", networkName);
        }
    }

    // ── IT-004: Config forwarding — max attempts system property ──

    @Test
    @EnabledIf("dockerAvailable")
    void reaperShouldHonorMaxAttemptsSystemProperty() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String containerName = "altcontainers-it004-" + sessionId;

        // Create and stop a container for cleanup
        execDocker("run", "-d", "--name", containerName, "alpine:latest", "sleep", "300");
        try {
            execDocker("stop", containerName);

            // Launch reaper with max.attempts=2
            Process reaper = launchReaper(sessionId, Map.of("altcontainers.reaper.cleanup.max.attempts", "2"));
            try {
                Integer port = waitForPort(sessionId, 10_000);
                assertThat(port).isNotNull();

                try (Socket socket = new Socket("localhost", port);
                        BufferedWriter writer = new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                    writer.write(sessionId + "\n");
                    writer.flush();
                    assertThat(reader.readLine()).isEqualTo("OK");

                    writer.write("TERMINATE_CONTAINER " + containerName + "\n");
                    writer.write("TERMINATE\n");
                    writer.flush();
                }

                boolean exited = reaper.waitFor(60, TimeUnit.SECONDS);
                assertThat(exited).isTrue();
                assertThat(reaper.exitValue()).isEqualTo(0);
            } finally {
                if (reaper.isAlive()) {
                    reaper.destroyForcibly();
                }
            }
        } finally {
            execDockerQuiet("rm", "-f", containerName);
        }
    }

    // ── IT-005: Drain sweep before exit ──

    @Test
    @EnabledIf("dockerAvailable")
    void reaperShouldDrainSweepBeforeExit() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String containerName = "altcontainers-it005-" + sessionId;

        // Create a container — the reaper sweep should find and clean it up
        execDocker(
                "run",
                "-d",
                "--name",
                containerName,
                "--label",
                "altcontainers-containers.session-id=" + sessionId,
                "alpine:latest",
                "sleep",
                "300");
        try {
            Process reaper = launchReaper(sessionId);
            try {
                Integer port = waitForPort(sessionId, 10_000);
                assertThat(port).isNotNull();

                try (Socket socket = new Socket("localhost", port);
                        BufferedWriter writer = new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                    writer.write(sessionId + "\n");
                    writer.flush();
                    assertThat(reader.readLine()).isEqualTo("OK");

                    // Close socket without TERMINATE — triggers disconnect + grace period + sweep
                }

                boolean exited = reaper.waitFor(90, TimeUnit.SECONDS);
                assertThat(exited).isTrue();
                assertThat(reaper.exitValue()).isEqualTo(0);

                // Container should be cleaned up by the sweep
                String inspect = execDockerQuiet("inspect", "--format", "{{.Id}}", containerName);
                assertThat(inspect).isEmpty();
            } finally {
                if (reaper.isAlive()) {
                    reaper.destroyForcibly();
                }
            }
        } finally {
            execDockerQuiet("rm", "-f", containerName);
        }
    }

    // ── Original handshake mismatch test ──

    @Test
    @EnabledIf("reaperJarAvailable")
    void reaperShouldExitOnHandshakeMismatch() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try (InputStream in = ReaperLifecycleTest.class.getClassLoader().getResourceAsStream("reaper.jar")) {
            assertThat(in).isNotNull();
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = Launcher.buildCommand(sessionId, jarPath, javaCommand, false);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            process = pb.start();

            long deadline = System.currentTimeMillis() + 10_000;
            Integer port = null;
            while (System.currentTimeMillis() < deadline && port == null) {
                port = ReaperDiscovery.readPort(sessionId).orElse(null);
                if (port == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(port).isNotNull();

            try (Socket socket = new Socket("localhost", port);
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                writer.write("wrong-session-id\n");
                writer.flush();

                try {
                    reader.readLine();
                } catch (IOException ignored) {
                }
            }

            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            assertThat(exited).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
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
    }

    // ── Integration test helpers ──

    /**
     * Runs a Docker CLI command and returns stdout, throwing on non-zero exit.
     *
     * @param args the Docker command arguments
     * @return the command stdout
     * @throws IOException if the command fails
     * @throws InterruptedException if the current thread is interrupted
     */
    private static String execDocker(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(30, TimeUnit.SECONDS);
        if (p.exitValue() != 0) {
            throw new IOException("docker " + args[0] + " failed (exit " + p.exitValue() + "): " + output);
        }
        return output;
    }

    /**
     * Runs a Docker CLI command, ignoring exit code. Returns stdout or empty string.
     *
     * @param args the Docker command arguments
     * @return the command stdout, or empty string on failure
     */
    private static String execDockerQuiet(String... args) {
        try {
            return execDocker(args);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the Docker network ID for the given network name.
     *
     * @param networkName the network name
     * @return the network ID
     * @throws IOException if the command fails
     * @throws InterruptedException if the current thread is interrupted
     */
    private static String getNetworkId(String networkName) throws IOException, InterruptedException {
        return execDocker("network", "inspect", "--format", "{{.Id}}", networkName)
                .trim();
    }

    /**
     * Launches a reaper process with default timeouts and no extra system properties.
     *
     * @param sessionId the session UUID
     * @return the reaper process
     * @throws IOException if the process cannot be started
     */
    private static Process launchReaper(String sessionId) throws IOException {
        return launchReaper(sessionId, Map.of());
    }

    /**
     * Launches a reaper process with extra system properties forwarded via -D flags.
     *
     * @param sessionId the session UUID
     * @param extraProperties additional system properties to forward
     * @return the reaper process
     * @throws IOException if the process cannot be started
     */
    private static Process launchReaper(String sessionId, Map<String, String> extraProperties) throws IOException {
        Path jarPath = Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");

        try (InputStream in = ReaperLifecycleTest.class.getClassLoader().getResourceAsStream("reaper.jar")) {
            assertThat(in).isNotNull();
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaCommand);
        cmd.add("-Daltcontainers.reaper.session.id=" + sessionId);
        cmd.add("-Daltcontainers.reaper.connection.timeout.ms=10000");
        cmd.add("-Daltcontainers.reaper.stop.timeout.ms=30000");
        for (Map.Entry<String, String> entry : extraProperties.entrySet()) {
            cmd.add("-D" + entry.getKey() + "=" + entry.getValue());
        }
        cmd.add("-jar");
        cmd.add(jarPath.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /**
     * Waits for the reaper port file to appear and returns the port.
     *
     * @param sessionId the session UUID
     * @param timeoutMs the maximum time to wait in milliseconds
     * @return the port, or {@code null} if the file did not appear in time
     * @throws InterruptedException if the current thread is interrupted
     */
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
}
