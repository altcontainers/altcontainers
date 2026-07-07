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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reaper I/O lifecycle.
 * Does not require Docker — tests socket handshake and port file I/O.
 */
class ReaperTest {

    private static Path portFilePath(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".port");
    }

    @Test
    void shouldWriteAndReadPortFile() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        int port = 12345;
        try {
            Path path = portFilePath(sessionId);
            Files.writeString(path, String.valueOf(port), StandardCharsets.UTF_8);
            assertThat(Files.exists(path)).isTrue();

            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            assertThat(Integer.parseInt(content)).isEqualTo(port);
        } finally {
            Files.deleteIfExists(portFilePath(sessionId));
        }
    }

    @Test
    void shouldDeletePortFile() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        Path path = portFilePath(sessionId);
        Files.writeString(path, "12345", StandardCharsets.UTF_8);
        assertThat(Files.exists(path)).isTrue();

        Files.deleteIfExists(path);
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void shouldCompleteHandshake() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        var serverSocket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"));
        int port = serverSocket.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                Socket client = serverSocket.accept();
                serverSocket.close();
                var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                var writer = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);

                String line = reader.readLine();
                if (sessionId.equals(line)) {
                    writer.write("OK\n");
                    writer.flush();
                }
                client.close();
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket socket = new Socket("localhost", port)) {
            var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.write(sessionId + "\n");
            writer.flush();

            String response = reader.readLine();
            assertThat(response).isEqualTo("OK");
        }
    }

    @Test
    void shouldRejectWrongSessionId() throws IOException {
        String expectedSessionId = "correct-session";
        String wrongSessionId = "wrong-session";

        var serverSocket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"));
        int port = serverSocket.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                Socket client = serverSocket.accept();
                serverSocket.close();
                var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                var writer = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);

                String line = reader.readLine();
                if (!expectedSessionId.equals(line)) {
                    writer.write("ERROR\n");
                    writer.flush();
                }
                client.close();
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket socket = new Socket("localhost", port)) {
            var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.write(wrongSessionId + "\n");
            writer.flush();

            String response = reader.readLine();
            assertThat(response).isEqualTo("ERROR");
        }
    }

    @Test
    void shouldTimeoutWhenNoConnectionArrives() throws IOException {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"))) {
            socket.setSoTimeout(500);
            long start = System.currentTimeMillis();
            assertThatThrownBy(socket::accept).isInstanceOf(SocketTimeoutException.class);
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void shouldHaveCorrectSessionIdLabel() {
        assertThat(ResourceLabels.SESSION_ID).isEqualTo("altcontainers-containers.session-id");
    }

    @Test
    void shouldNotCrashDuringLogbackConfiguration() throws IOException {
        // Given configureLogback's catch block that writes to System.err
        // when Logback fails to start, verify that the method completes
        // normally for a valid config (regression guard).
        Path tmpDir = Files.createTempDirectory("reaper-config-test-");
        try {
            String logPath = tmpDir.resolve("test-reaper.log").toString();
            System.setProperty("altcontainers.reaper.log.directory", tmpDir.toString());
            // Should not throw
            Reaper.configureLogback(logPath);
        } finally {
            System.clearProperty("altcontainers.reaper.log.directory");
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }
}
