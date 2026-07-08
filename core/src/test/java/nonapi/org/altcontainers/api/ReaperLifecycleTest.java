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
import java.util.List;
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

            try (Socket socket = new Socket("localhost", port)) {
                var writer =
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

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

            try (Socket socket = new Socket("localhost", port)) {
                var writer =
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

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

            try (Socket socket = new Socket("localhost", port)) {
                var writer =
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

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
}
