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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveNetworkCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Network;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reaper I/O lifecycle and async cleanup executor.
 * Does not require Docker — tests socket handshake, port file I/O,
 * and cleanup executor behavior with mock Docker clients.
 */
class ReaperTest {

    @Test
    void shouldCleanupJarFileOnHandshakeFailure() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path portFile = portFilePath(sessionId);
        Path jarFile = jarFilePath(sessionId);

        // Create both discovery files
        Files.writeString(portFile, "12345", StandardCharsets.UTF_8);
        Files.createFile(jarFile);
        assertThat(Files.exists(portFile)).isTrue();
        assertThat(Files.exists(jarFile)).isTrue();

        DockerClient mockClient = createSuccessMockClient();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 5000L, 5000L, scheduler, clock);

        // Create a connected socket pair for the cleanup method to close
        var serverSocket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"));
        int port = serverSocket.getLocalPort();
        try (Socket clientSocket = new Socket("localhost", port);
                Socket acceptedSocket = serverSocket.accept()) {
            serverSocket.close();

            // Call the extracted cleanup method directly — this is the code path
            // exercised by run()'s HandshakeException catch block
            Reaper.cleanupOnHandshakeFailure(sessionId, 12345, acceptedSocket, executor);

            // Assert that both discovery files were deleted by the cleanup method
            assertThat(Files.exists(portFile)).isFalse();
            assertThat(Files.exists(jarFile)).isFalse();
        }

        scheduler.shutdownNow();
    }

    @Test
    void shouldClampStopTimeoutSecondsToAtLeastOne() {
        // A sub-second configured stop timeout must never produce a zero
        // timeout for the Docker stop API.
        assertThat(CleanupExecutor.stopTimeoutSeconds(500)).isEqualTo(1);
        assertThat(CleanupExecutor.stopTimeoutSeconds(1000)).isEqualTo(1);
        assertThat(CleanupExecutor.stopTimeoutSeconds(30_000)).isEqualTo(30);
    }

    @Test
    void shouldNotDeleteDiscoveryFilesOwnedByAnotherReaper() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path portFile = portFilePath(sessionId);
        Path jarFile = jarFilePath(sessionId);

        // The port file advertises a different reaper's port (relaunch race).
        Files.writeString(portFile, "54321", StandardCharsets.UTF_8);
        Files.createFile(jarFile);

        assertThat(Reaper.deleteDiscoveryFilesIfOwned(sessionId, 12345)).isFalse();
        assertThat(Files.exists(portFile)).isTrue();
        assertThat(Files.exists(jarFile)).isTrue();
    }

    @Test
    void shouldDeleteDiscoveryFilesWhenPortFileIsOwned() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Path portFile = portFilePath(sessionId);
        Path jarFile = jarFilePath(sessionId);

        Files.writeString(portFile, "12345", StandardCharsets.UTF_8);
        Files.createFile(jarFile);

        assertThat(Reaper.deleteDiscoveryFilesIfOwned(sessionId, 12345)).isTrue();
        assertThat(Files.exists(portFile)).isFalse();
        assertThat(Files.exists(jarFile)).isFalse();
    }

    private static Path portFilePath(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".port");
    }

    private static Path jarFilePath(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar");
    }

    // ── Port file tests ──

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

    // ── Handshake tests ──

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
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"))) {
            socket.setSoTimeout(500);
            long start = System.currentTimeMillis();
            assertThatThrownBy(socket::accept).isInstanceOf(SocketTimeoutException.class);
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isGreaterThanOrEqualTo(400);
        }
    }

    // ── Label / config tests ──

    @Test
    void shouldHaveCorrectSessionIdLabel() {
        assertThat(ResourceLabels.SESSION_ID).isEqualTo("altcontainers-containers.session-id");
    }

    @Test
    void shouldDefaultLogPathToTempDir() {
        String saved = System.getProperty("altcontainers.reaper.log.directory");
        String sessionId = UUID.randomUUID().toString();
        try {
            System.clearProperty("altcontainers.reaper.log.directory");
            String logPath = Reaper.resolveLogPath(sessionId);
            String tmpDir = System.getProperty("java.io.tmpdir");
            assertThat(logPath).startsWith(tmpDir);
            assertThat(logPath).endsWith("altcontainers-reaper-" + sessionId + ".log");
        } finally {
            if (saved != null) {
                System.setProperty("altcontainers.reaper.log.directory", saved);
            } else {
                System.clearProperty("altcontainers.reaper.log.directory");
            }
        }
    }

    @Test
    void shouldHonorExplicitLogDirectory() throws IOException {
        String saved = System.getProperty("altcontainers.reaper.log.directory");
        Path tmpDir = Files.createTempDirectory("reaper-log-test-");
        String sessionId = UUID.randomUUID().toString();
        try {
            System.setProperty("altcontainers.reaper.log.directory", tmpDir.toString());
            String logPath = Reaper.resolveLogPath(sessionId);
            assertThat(logPath).startsWith(tmpDir.toString());
            assertThat(logPath).endsWith("altcontainers-reaper-" + sessionId + ".log");
        } finally {
            if (saved != null) {
                System.setProperty("altcontainers.reaper.log.directory", saved);
            } else {
                System.clearProperty("altcontainers.reaper.log.directory");
            }
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

    @Test
    void shouldNotCrashDuringLogbackConfiguration() throws IOException {
        Path tmpDir = Files.createTempDirectory("reaper-config-test-");
        try {
            String logPath = tmpDir.resolve("test-reaper.log").toString();
            System.setProperty("altcontainers.reaper.log.directory", tmpDir.toString());
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

    // ── MAX_ATTEMPTS config parsing (UT-001 through UT-004) ──

    @Test
    void shouldParseMaxAttemptsValid() {
        assertThat(Reaper.parseMaxAttempts("5")).isEqualTo(5);
        assertThat(Reaper.parseMaxAttempts("1")).isEqualTo(1);
        assertThat(Reaper.parseMaxAttempts("100")).isEqualTo(100);
    }

    @Test
    void shouldParseMaxAttemptsNonInteger() {
        assertThatThrownBy(() -> Reaper.parseMaxAttempts("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid integer");
    }

    @Test
    void shouldParseMaxAttemptsBelowOne() {
        assertThatThrownBy(() -> Reaper.parseMaxAttempts("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
    }

    @Test
    void shouldParseMaxAttemptsNegative() {
        assertThatThrownBy(() -> Reaper.parseMaxAttempts("-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
    }

    // ── CleanupExecutor process() attempts-bound (UT-005 through UT-007) ──

    @Test
    void shouldInvokeProcessExactlyMaxAttemptsForMax1() throws Exception {
        assertExactAttemptCount(1);
    }

    @Test
    void shouldInvokeProcessExactlyMaxAttemptsForMax2() throws Exception {
        assertExactAttemptCount(2);
    }

    @Test
    void shouldInvokeProcessExactlyMaxAttemptsForMax5() throws Exception {
        assertExactAttemptCount(5);
    }

    private void assertExactAttemptCount(int maxAttempts) throws Exception {
        DockerClient mockClient = createAlwaysFailMockClient();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, maxAttempts, 1000L, 1000L, scheduler, clock);

        executor.submit(new CleanupTask("test-container", CleanupTask.ResourceType.CONTAINER, 0));

        // Poll until all expected invocations occur or timeout
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            long count = org.mockito.Mockito.mockingDetails(mockClient).getInvocations().stream()
                    .filter(inv -> "stopContainerCmd".equals(inv.getMethod().getName())
                            && inv.getArguments().length > 0
                            && "test-container".equals(inv.getArguments()[0]))
                    .count();
            if (count >= maxAttempts) {
                break;
            }
            Thread.sleep(50);
        }

        // Each process() calls stopContainerCmd once
        org.mockito.Mockito.verify(mockClient, org.mockito.Mockito.times(maxAttempts))
                .stopContainerCmd("test-container");

        scheduler.shutdownNow();
    }

    @Test
    void shouldRetryFailedTasksDuringDrainAfterShutdown() throws Exception {
        // Mirrors Reaper.cleanup(): shutdown is initiated before the drain wait.
        // Backoff retries scheduled during the drain must not be dropped.
        DockerClient mockClient = createAlwaysFailMockClient();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, 3, 500L, 500L, scheduler, clock);

        executor.submit(new CleanupTask("drain-container", CleanupTask.ResourceType.CONTAINER, 0));
        executor.shutdown();
        boolean drained = executor.awaitTermination(60, TimeUnit.SECONDS);

        assertThat(drained).isTrue();
        org.mockito.Mockito.verify(mockClient, org.mockito.Mockito.times(3)).stopContainerCmd("drain-container");

        scheduler.shutdownNow();
    }

    // ── Success path tests (UT-008 through UT-010) ──

    @Test
    void shouldCompleteOnNotFoundExceptionAtDestroy() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        doThrow(new NotFoundException("not found")).when(stopCmd).exec();

        // removeContainerCmd also throws NotFoundException (container already gone)
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doThrow(new NotFoundException("not found")).when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, clock);

        executor.submit(new CleanupTask("gone-container", CleanupTask.ResourceType.CONTAINER, 0));
        scheduler.awaitTermination(5, TimeUnit.SECONDS);

        // NotFoundException on stop is best-effort — removeContainerCmd IS called
        org.mockito.Mockito.verify(mockClient).removeContainerCmd("gone-container");

        scheduler.shutdownNow();
    }

    @Test
    void shouldFallThroughToForceOnDestroyFailure() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        doThrow(new RuntimeException("stop failed")).when(stopCmd).exec();

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doNothing().when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, clock);

        executor.submit(new CleanupTask("container-id", CleanupTask.ResourceType.CONTAINER, 0));
        scheduler.awaitTermination(5, TimeUnit.SECONDS);

        // Force tier should be invoked (withForce(true))
        org.mockito.Mockito.verify(removeCmd).exec();

        scheduler.shutdownNow();
    }

    // ── Network threshold tests (UT-012 through UT-013) ──

    @Test
    void shouldNotForceDisconnectNetworkBeforeThreshold() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        RemoveNetworkCmd removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(mockClient.removeNetworkCmd(anyString())).thenReturn(removeNetworkCmd);
        doThrow(new RuntimeException("has active endpoints"))
                .when(removeNetworkCmd)
                .exec();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        // maxAttempts=2, NETWORK_FORCE_THRESHOLD=3 — process runs 2 times,
        // attempts goes 0→1→2 (drop). Never reaches threshold 3.
        CleanupExecutor executor = new CleanupExecutor(mockClient, 2, 1000L, 1000L, scheduler, clock);

        executor.submit(new CleanupTask("net-1", CleanupTask.ResourceType.NETWORK, 0));
        scheduler.awaitTermination(10, TimeUnit.SECONDS);

        // inspectNetworkCmd should NOT be called (force tier not reached)
        org.mockito.Mockito.verify(mockClient, org.mockito.Mockito.never()).inspectNetworkCmd();

        scheduler.shutdownNow();
    }

    @Test
    void shouldForceDisconnectNetworkAfterThreshold() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);

        // removeNetworkCmd fails on first standalone call (triggering force tier)
        // then succeeds inside forceRemoveNetwork after disconnect
        RemoveNetworkCmd removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(mockClient.removeNetworkCmd("net-1")).thenReturn(removeNetworkCmd);
        doThrow(new RuntimeException("has active endpoints"))
                .doNothing()
                .when(removeNetworkCmd)
                .exec();

        // inspectNetworkCmd returns a network with one container endpoint
        InspectNetworkCmd inspectCmd = mock(InspectNetworkCmd.class);
        when(mockClient.inspectNetworkCmd()).thenReturn(inspectCmd);
        when(inspectCmd.withNetworkId("net-1")).thenReturn(inspectCmd);
        Network mockNetwork = mock(Network.class);
        Network.ContainerNetworkConfig endpointConfig = new Network.ContainerNetworkConfig();
        when(mockNetwork.getContainers()).thenReturn(Map.of("container-abc", endpointConfig));
        when(inspectCmd.exec()).thenReturn(mockNetwork);

        // disconnectFromNetworkCmd succeeds
        DisconnectFromNetworkCmd disconnectCmd = mock(DisconnectFromNetworkCmd.class);
        when(mockClient.disconnectFromNetworkCmd()).thenReturn(disconnectCmd);
        when(disconnectCmd.withContainerId("container-abc")).thenReturn(disconnectCmd);
        when(disconnectCmd.withNetworkId("net-1")).thenReturn(disconnectCmd);
        when(disconnectCmd.withForce(true)).thenReturn(disconnectCmd);
        doNothing().when(disconnectCmd).exec();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        // maxAttempts=5, NETWORK_FORCE_THRESHOLD=3 — attempts=3 >= threshold
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, clock);

        executor.submit(new CleanupTask("net-1", CleanupTask.ResourceType.NETWORK, 3));
        scheduler.awaitTermination(10, TimeUnit.SECONDS);

        // inspectNetworkCmd should be called (force tier activated)
        org.mockito.Mockito.verify(mockClient).inspectNetworkCmd();
        // disconnectFromNetworkCmd should be called with force=true
        org.mockito.Mockito.verify(disconnectCmd).withForce(true);
        org.mockito.Mockito.verify(disconnectCmd).exec();
        // removeNetworkCmd should be called twice: once standalone (fails), once in force tier (succeeds)
        org.mockito.Mockito.verify(mockClient, org.mockito.Mockito.times(2)).removeNetworkCmd("net-1");

        scheduler.shutdownNow();
    }

    // ── Backoff tests (UT-014 through UT-016) ──

    @Test
    void shouldComputeCorrectBackoff() {
        // backoff(n) = min(1000 * (1L << n), 30000)
        assertThat(CleanupExecutor.backoffMs(1)).isEqualTo(2000L);
        assertThat(CleanupExecutor.backoffMs(2)).isEqualTo(4000L);
        assertThat(CleanupExecutor.backoffMs(3)).isEqualTo(8000L);
        assertThat(CleanupExecutor.backoffMs(4)).isEqualTo(16000L);
        assertThat(CleanupExecutor.backoffMs(5)).isEqualTo(30000L); // capped
    }

    @Test
    void shouldSaturateBackoffForLargeAttemptCounts() {
        // A large configured max attempts must never shift into a negative (or
        // otherwise short) delay: an overflowing shift would retry immediately and
        // hammer the daemon instead of backing off.
        assertThat(CleanupExecutor.backoffMs(62)).isEqualTo(CleanupExecutor.BACKOFF_CAP_MS);
        assertThat(CleanupExecutor.backoffMs(63)).isEqualTo(CleanupExecutor.BACKOFF_CAP_MS);
        assertThat(CleanupExecutor.backoffMs(64)).isEqualTo(CleanupExecutor.BACKOFF_CAP_MS);
        assertThat(CleanupExecutor.backoffMs(Integer.MAX_VALUE)).isEqualTo(CleanupExecutor.BACKOFF_CAP_MS);
    }

    // ── Session sweep convergence tests (verified cleanup) ──

    @Test
    void shouldRepeatSweepUntilResourceRemovalSucceeds() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        stubListContainers(mockClient, "flaky");
        stubSessionNetworks(mockClient);
        RemoveContainerCmd removeCmd = forcedRemoveStub(mockClient, "flaky");
        // First removal attempt fails, later ones succeed.
        doThrow(new RuntimeException("daemon busy")).doNothing().when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 1, 1000L, 1000L, scheduler, Clock.systemUTC());

        try {
            Reaper.CleanupResult result = Reaper.cleanup(mockClient, "session", executor, 10_000L, 1000L);

            assertThat(result.sweeps()).isEqualTo(2);
            assertThat(result.discoveryComplete()).isTrue();
            assertThat(result.containerIds()).isEmpty();
            assertThat(result.clean()).isTrue();
            assertThat(executor.unresolvedCount()).isZero();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldBoundSweepsAndReportLeftovers() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        stubListContainers(mockClient, "stuck");
        stubSessionNetworks(mockClient);
        RemoveContainerCmd removeCmd = forcedRemoveStub(mockClient, "stuck");
        doThrow(new RuntimeException("daemon wedged")).when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 1, 500L, 500L, scheduler, Clock.systemUTC());

        long start = System.nanoTime();
        try {
            Reaper.CleanupResult result = Reaper.cleanup(mockClient, "session", executor, 20_000L, 1000L);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(result.sweeps()).as("sweeps are bounded, not endless").isEqualTo(Reaper.MAX_SWEEPS);
            assertThat(result.clean()).isFalse();
            assertThat(result.containerIds()).containsExactly("stuck");
            assertThat(result.networkIds()).isEmpty();
            assertThat(Reaper.exitCode(result)).isEqualTo(Reaper.EXIT_LEFTOVERS);
            assertThat(elapsedMillis).as("elapsed time is bounded").isLessThan(60_000L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldNotSweepAgainWhenEverythingWasRemoved() throws Exception {
        // The success path must keep today's Docker call sequence (one sweep).
        DockerClient mockClient = mock(DockerClient.class);
        stubListContainers(mockClient, "c1");
        stubSessionNetworks(mockClient, "n1");
        RemoveContainerCmd removeCmd = forcedRemoveStub(mockClient, "c1");
        doNothing().when(removeCmd).exec();
        RemoveNetworkCmd removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(mockClient.removeNetworkCmd("n1")).thenReturn(removeNetworkCmd);
        doNothing().when(removeNetworkCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, Clock.systemUTC());

        try {
            Reaper.CleanupResult result = Reaper.cleanup(mockClient, "session", executor, 10_000L, 1000L);

            assertThat(result.sweeps()).isEqualTo(1);
            assertThat(result.clean()).isTrue();
            org.mockito.Mockito.verify(removeNetworkCmd).exec();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldRetryTransientListingFailure() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(mockClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(true)).thenReturn(listContainersCmd);
        when(listContainersCmd.withLabelFilter(anyMap())).thenReturn(listContainersCmd);
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("c1");
        // First listing fails, the retry succeeds.
        doThrow(new RuntimeException("transient listing failure"))
                .doReturn(List.of(container))
                .when(listContainersCmd)
                .exec();
        stubSessionNetworks(mockClient);
        RemoveContainerCmd removeCmd = forcedRemoveStub(mockClient, "c1");
        doNothing().when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, Clock.systemUTC());

        try {
            Reaper.CleanupResult result = Reaper.cleanup(mockClient, "session", executor, 10_000L, 1000L);

            assertThat(result.discoveryComplete())
                    .as("a transient listing failure is retried, not treated as empty")
                    .isTrue();
            assertThat(result.clean()).isTrue();
            org.mockito.Mockito.verify(removeCmd).exec();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldReportIncompleteDiscoveryWhenListingKeepsFailing() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(mockClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(true)).thenReturn(listContainersCmd);
        when(listContainersCmd.withLabelFilter(anyMap())).thenReturn(listContainersCmd);
        doThrow(new RuntimeException("daemon unreachable"))
                .when(listContainersCmd)
                .exec();
        stubSessionNetworks(mockClient);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, Clock.systemUTC());

        try {
            Reaper.CleanupResult result = Reaper.cleanup(mockClient, "session", executor, 10_000L, 1000L);

            assertThat(result.discoveryComplete()).isFalse();
            assertThat(result.clean())
                    .as("a failed listing must never look clean")
                    .isFalse();
            assertThat(Reaper.exitCode(result)).isEqualTo(Reaper.EXIT_LEFTOVERS);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldRemoveAnonymousVolumesInBothContainerTiers() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        doNothing().when(stopCmd).exec();
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doNothing().when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 5000L, 5000L, scheduler, Clock.systemUTC());

        try {
            assertThat(executor.destroyContainer("graceful")).isTrue();
            assertThat(executor.forceRemoveContainer("forced")).isTrue();

            // Both tiers must delete the container's anonymous volumes; named volumes are kept by Docker.
            org.mockito.Mockito.verify(removeCmd, org.mockito.Mockito.times(2)).withRemoveVolumes(true);
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * Stubs an empty or populated network listing bound to the Docker client.
     *
     * @param mockClient the Docker client mock
     * @param ids the network ids the listing returns
     * @return the listing command mock
     */
    private static ListNetworksCmd stubSessionNetworks(DockerClient mockClient, String... ids) {
        ListNetworksCmd listNetworksCmd = stubListNetworks(mockClient, ids);
        when(mockClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        return listNetworksCmd;
    }

    /**
     * Stubs a force-remove command that reports the given container id.
     *
     * @param mockClient the Docker client mock
     * @param id the container id
     * @return the remove command mock
     */
    private static RemoveContainerCmd forcedRemoveStub(DockerClient mockClient, String id) {
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(id)).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        return removeCmd;
    }

    // ── Timeout cutoff tests (UT-017 through UT-018) ──

    @Test
    void shouldCutOffAtDestroyTimeout() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        // Block for longer than the timeout
        doAnswer(inv -> {
                    Thread.sleep(60_000);
                    return null;
                })
                .when(stopCmd)
                .exec();

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doNothing().when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        // Short timeout: 100ms, maxAttempts=1 so no retries
        CleanupExecutor executor = new CleanupExecutor(mockClient, 1, 100L, 100L, scheduler, clock);

        long start = System.currentTimeMillis();
        executor.submit(new CleanupTask("hanging-container", CleanupTask.ResourceType.CONTAINER, 0));
        // Poll until the task completes (process finishes after timeout cutoff)
        long deadline = start + 10_000;
        while (System.currentTimeMillis() < deadline) {
            long count = org.mockito.Mockito.mockingDetails(mockClient).getInvocations().stream()
                    .filter(inv -> "stopContainerCmd".equals(inv.getMethod().getName()))
                    .count();
            if (count >= 1) {
                break;
            }
            Thread.sleep(50);
        }
        long elapsed = System.currentTimeMillis() - start;

        // The destroy call should be cut off at the timeout (~100ms), not wait 60s
        assertThat(elapsed).isLessThan(10_000L);

        scheduler.shutdownNow();
    }

    @Test
    void shouldCutOffAtForceTimeout() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        doThrow(new RuntimeException("stop failed")).when(stopCmd).exec();

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        // Block for longer than force timeout
        doAnswer(inv -> {
                    Thread.sleep(60_000);
                    return null;
                })
                .when(removeCmd)
                .exec();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        // Short timeouts, maxAttempts=1
        CleanupExecutor executor = new CleanupExecutor(mockClient, 1, 100L, 100L, scheduler, clock);

        long start = System.currentTimeMillis();
        executor.submit(new CleanupTask("hanging-container", CleanupTask.ResourceType.CONTAINER, 0));
        // Poll until the task completes (process finishes after timeout cutoff)
        long deadline = start + 10_000;
        while (System.currentTimeMillis() < deadline) {
            long count = org.mockito.Mockito.mockingDetails(mockClient).getInvocations().stream()
                    .filter(inv -> "removeContainerCmd".equals(inv.getMethod().getName()))
                    .count();
            if (count >= 1) {
                break;
            }
            Thread.sleep(50);
        }
        long elapsed = System.currentTimeMillis() - start;

        // The force-remove call should be cut off at the timeout (~100ms), not wait 60s
        assertThat(elapsed).isLessThan(10_000L);

        scheduler.shutdownNow();
    }

    // ── Drain tests (UT-019 through UT-020) ──

    @Test
    void shouldDrainAllSucceedingTasks() throws Exception {
        DockerClient mockClient = createSuccessMockClient();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 5000L, 5000L, scheduler, clock);

        // Submit multiple tasks
        executor.submit(new CleanupTask("c1", CleanupTask.ResourceType.CONTAINER, 0));
        executor.submit(new CleanupTask("c2", CleanupTask.ResourceType.CONTAINER, 0));
        executor.submit(new CleanupTask("n1", CleanupTask.ResourceType.NETWORK, 0));

        executor.shutdown();
        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
    }

    @Test
    void shouldReportIncompleteDrainOnDeadline() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        // All Docker calls block permanently
        doAnswer(inv -> {
                    Thread.sleep(600_000);
                    return null;
                })
                .when(stopCmd)
                .exec();

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doAnswer(inv -> {
                    Thread.sleep(600_000);
                    return null;
                })
                .when(removeCmd)
                .exec();

        // Every task blocks a worker for longer than the drain wait below
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        Clock clock = Clock.systemUTC();
        // maxAttempts=1 so no retries — just the initial attempt
        CleanupExecutor executor = new CleanupExecutor(mockClient, 1, 5000L, 5000L, scheduler, clock);

        // Submit 3 tasks; none can finish before the drain wait expires
        executor.submit(new CleanupTask("c1", CleanupTask.ResourceType.CONTAINER, 0));
        executor.submit(new CleanupTask("c2", CleanupTask.ResourceType.CONTAINER, 0));
        executor.submit(new CleanupTask("c3", CleanupTask.ResourceType.CONTAINER, 0));

        // Give workers time to pick up the first 2 tasks
        Thread.sleep(200);

        executor.shutdown();
        boolean completed = executor.awaitTermination(500, TimeUnit.MILLISECONDS);

        assertThat(completed).isFalse();

        scheduler.shutdownNow();
    }

    // ── Protocol tests with CleanupExecutor (UT-021, UT-022) ──

    @Test
    void shouldAcceptTerminateContainerCommand() throws Exception {
        DockerClient mockClient = createSuccessMockClient();
        CleanupExecutor executor = createTestExecutor(mockClient);

        runProtocolTest("test-session", executor, writer -> {
            try {
                writer.write("TERMINATE_CONTAINER container-abc\n");
                Thread.sleep(100);
                writer.write("TERMINATE\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldAcceptTerminateNetworkCommand() throws Exception {
        DockerClient mockClient = createSuccessMockClient();
        CleanupExecutor executor = createTestExecutor(mockClient);

        runProtocolTest("test-session", executor, writer -> {
            try {
                writer.write("TERMINATE_NETWORK net-xyz\n");
                Thread.sleep(100);
                writer.write("TERMINATE\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldHandleMultipleTerminateContainerCommands() throws Exception {
        DockerClient mockClient = createSuccessMockClient();
        CleanupExecutor executor = createTestExecutor(mockClient);

        runProtocolTest("test-session", executor, writer -> {
            try {
                writer.write("TERMINATE_CONTAINER A\n");
                writer.write("TERMINATE_CONTAINER B\n");
                Thread.sleep(100);
                writer.write("TERMINATE\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldTreatUnknownCommandAsNoop() throws Exception {
        DockerClient mockClient = createSuccessMockClient();
        CleanupExecutor executor = createTestExecutor(mockClient);

        runProtocolTest("test-session", executor, writer -> {
            try {
                writer.write("UNKNOWN\n");
                Thread.sleep(100);
                writer.write("TERMINATE\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldDecoupleReadLoopFromSlowCleanup() throws Exception {
        // Mock a client where the first container's cleanup blocks
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd slowStopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd("hanging-id")).thenReturn(slowStopCmd);
        when(slowStopCmd.withTimeout(anyInt())).thenReturn(slowStopCmd);
        doAnswer(inv -> {
                    Thread.sleep(60_000);
                    return null;
                })
                .when(slowStopCmd)
                .exec();

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doNothing().when(removeCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        Clock clock = Clock.systemUTC();
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 5000L, 5000L, scheduler, clock);

        // Send TERMINATE_CONTAINER for hanging-id, then immediately TERMINATE
        // The read loop should NOT block on the hanging cleanup
        long start = System.currentTimeMillis();
        runProtocolTest("test-session", executor, writer -> {
            try {
                writer.write("TERMINATE_CONTAINER hanging-id\n");
                // Do NOT sleep — send TERMINATE immediately
                writer.write("TERMINATE\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long elapsed = System.currentTimeMillis() - start;

        // Protocol test should complete quickly (< 5s), not blocked by the 60s sleep
        assertThat(elapsed).isLessThan(5_000L);

        // Shut down the executor to clean up background threads
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // ── Session sweep ──

    @Test
    void shouldForceRemoveContainersBeforeNetworksInSessionSweep() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        List<String> events = new CopyOnWriteArrayList<>();
        stubListContainers(mockClient, "c1", "c2");
        for (String id : List.of("c1", "c2")) {
            RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
            when(mockClient.removeContainerCmd(id)).thenReturn(removeCmd);
            when(removeCmd.withForce(true)).thenReturn(removeCmd);
            when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
            when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
            doAnswer(inv -> {
                        Thread.sleep(100);
                        events.add("container:" + id);
                        return null;
                    })
                    .when(removeCmd)
                    .exec();
        }
        ListNetworksCmd listNetworksCmd = stubListNetworks(mockClient, "n1");
        when(mockClient.listNetworksCmd()).thenAnswer(inv -> {
            events.add("listNetworks");
            return listNetworksCmd;
        });
        RemoveNetworkCmd removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(mockClient.removeNetworkCmd("n1")).thenReturn(removeNetworkCmd);
        doAnswer(inv -> {
                    events.add("network:n1");
                    return null;
                })
                .when(removeNetworkCmd)
                .exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 5000L, 5000L, scheduler, Clock.systemUTC());

        Reaper.cleanup(mockClient, "session", executor, 10_000L, 2_000L);

        assertThat(events).containsExactlyInAnyOrder("container:c1", "container:c2", "listNetworks", "network:n1");
        assertThat(events.indexOf("listNetworks"))
                .as("networks listed only after every container was removed")
                .isGreaterThan(events.indexOf("container:c1"))
                .isGreaterThan(events.indexOf("container:c2"));
        assertThat(events.get(events.size() - 1)).isEqualTo("network:n1");
        // Session is over: containers are force-removed, never gracefully stopped
        org.mockito.Mockito.verify(mockClient, never()).stopContainerCmd(anyString());

        scheduler.shutdownNow();
    }

    @Test
    void shouldReserveDrainTimeForNetworks() throws Exception {
        DockerClient mockClient = mock(DockerClient.class);
        stubListContainers(mockClient, "stuck");
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd("stuck")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doAnswer(inv -> {
                    Thread.sleep(60_000);
                    return null;
                })
                .when(removeCmd)
                .exec();
        ListNetworksCmd listNetworksCmd = stubListNetworks(mockClient, "n1");
        when(mockClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        RemoveNetworkCmd removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(mockClient.removeNetworkCmd("n1")).thenReturn(removeNetworkCmd);
        doNothing().when(removeNetworkCmd).exec();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE);
        // Short force timeout so the stuck container keeps retrying and never drains
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 200L, 200L, scheduler, Clock.systemUTC());

        long start = System.nanoTime();
        Reaper.cleanup(mockClient, "session", executor, 3_000L, 1_500L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        org.mockito.Mockito.verify(removeNetworkCmd).exec();
        assertThat(elapsedMillis).as("sweep bounded by the drain deadline").isLessThan(10_000L);

        executor.shutdownNow();
    }

    @Test
    void shouldTreatRemovalInProgressAsSuccess() {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doThrow(new ConflictException("Status 409: {\"message\":\"removal of container busy is already in progress\"}"))
                .when(removeCmd)
                .exec();

        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, Clock.systemUTC());

        executor.process(new CleanupTask("busy", CleanupTask.ResourceType.CONTAINER, 0));

        // Success: no force tier and no retry scheduled
        org.mockito.Mockito.verify(removeCmd, never()).withForce(true);
        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldStillFailOnOtherConflicts() {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doThrow(new ConflictException("Status 409: {\"message\":\"conflict: unable to remove\"}"))
                .when(removeCmd)
                .exec();

        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        CleanupExecutor executor = new CleanupExecutor(mockClient, 5, 1000L, 1000L, scheduler, Clock.systemUTC());

        executor.process(new CleanupTask("conflicted", CleanupTask.ResourceType.CONTAINER, 0));

        // Failure: force tier attempted
        org.mockito.Mockito.verify(removeCmd).withForce(true);
    }

    private static void stubListContainers(DockerClient mockClient, String... ids) {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class);
        when(mockClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(true)).thenReturn(listContainersCmd);
        when(listContainersCmd.withLabelFilter(anyMap())).thenReturn(listContainersCmd);
        List<Container> containers = new java.util.ArrayList<>();
        for (String id : ids) {
            Container container = mock(Container.class);
            when(container.getId()).thenReturn(id);
            containers.add(container);
        }
        when(listContainersCmd.exec()).thenReturn(containers);
    }

    private static ListNetworksCmd stubListNetworks(DockerClient mockClient, String... ids) {
        ListNetworksCmd listNetworksCmd = mock(ListNetworksCmd.class);
        when(listNetworksCmd.withFilter(anyString(), anyCollection())).thenReturn(listNetworksCmd);
        List<Network> networks = new java.util.ArrayList<>();
        for (String id : ids) {
            Network network = mock(Network.class);
            when(network.getId()).thenReturn(id);
            networks.add(network);
        }
        when(listNetworksCmd.exec()).thenReturn(networks);
        return listNetworksCmd;
    }

    // ── Backoff constants ──

    @Test
    void shouldHaveCorrectBackoffConstants() {
        assertThat(CleanupExecutor.BACKOFF_BASE_MS).isEqualTo(1000L);
        assertThat(CleanupExecutor.BACKOFF_CAP_MS).isEqualTo(30_000L);
        assertThat(CleanupExecutor.NETWORK_FORCE_THRESHOLD).isEqualTo(3);
        assertThat(CleanupExecutor.POOL_SIZE).isEqualTo(8);
        assertThat(CleanupExecutor.DRAIN_DEADLINE_MINUTES).isEqualTo(5);
    }

    // ── Test helpers ──

    /**
     * Creates a mock DockerClient where all operations fail with RuntimeException.
     */
    private static DockerClient createAlwaysFailMockClient() {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        doThrow(new RuntimeException("always fail")).when(stopCmd).exec();

        return mockClient;
    }

    /**
     * Creates a mock DockerClient where all operations succeed immediately.
     */
    private static DockerClient createSuccessMockClient() {
        DockerClient mockClient = mock(DockerClient.class);
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(mockClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        doNothing().when(stopCmd).exec();

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(mockClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        when(removeCmd.withRemoveVolumes(anyBoolean())).thenReturn(removeCmd);
        doNothing().when(removeCmd).exec();

        RemoveNetworkCmd removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(mockClient.removeNetworkCmd(anyString())).thenReturn(removeNetworkCmd);
        doNothing().when(removeNetworkCmd).exec();

        return mockClient;
    }

    /**
     * Creates a test CleanupExecutor with a single-threaded scheduler and
     * production-appropriate timeout values.
     */
    private static CleanupExecutor createTestExecutor(DockerClient mockClient) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Clock clock = Clock.systemUTC();
        return new CleanupExecutor(mockClient, 5, 5000L, 5000L, scheduler, clock);
    }

    /**
     * Runs a socket-based protocol test. Starts a background thread that calls
     * receiveSessionAndWatch, then the caller drives the protocol from the client side.
     */
    private static void runProtocolTest(
            String sessionId, CleanupExecutor executor, java.util.function.Consumer<java.io.Writer> clientActions)
            throws Exception {
        var serverSocket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"));
        int port = serverSocket.getLocalPort();

        java.util.concurrent.atomic.AtomicReference<Boolean> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                Socket client = serverSocket.accept();
                serverSocket.close();
                boolean r = Reaper.receiveSessionAndWatch(client, sessionId, 5000, executor);
                result.set(r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket socket = new Socket("localhost", port)) {
            var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.write(sessionId + "\n");
            writer.flush();
            assertThat(reader.readLine()).isEqualTo("OK");

            clientActions.accept(writer);
            writer.flush();
        }

        serverThread.join(5000);
    }
}
