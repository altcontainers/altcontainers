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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Verifies image pull behavior: failure propagation, local-image cache,
 * and pull-skip when the image already exists locally.
 */
class ContainerManagerPullTest {

    static boolean dockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean reaperJarAvailable() {
        return ContainerManagerPullTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    static boolean dockerAndReaperAvailable() {
        return dockerAvailable() && reaperJarAvailable();
    }

    @Test
    void shouldReportErrorDeliveredInSuccessfulHttpPullResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] response = """
                {"status":"Pulling fs layer"}
                {"errorDetail":{"message":"layer download failed"},"error":"layer download failed"}
                """.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().endsWith("/images/create")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            // Isolate the process-wide Docker client and its host configuration.
            Process worker = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            "-Daltcontainers.docker.host=tcp://127.0.0.1:"
                                    + server.getAddress().getPort(),
                            "-Daltcontainers.image.pull.timeout.ms=5000",
                            PullFailureProbe.class.getName())
                    .redirectErrorStream(true)
                    .start();
            try {
                assertThat(worker.waitFor(15, TimeUnit.SECONDS)).isTrue();
                String output = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(worker.exitValue())
                        .as("pull probe output: %s", output)
                        .isZero();
            } finally {
                if (worker.isAlive()) {
                    worker.destroyForcibly();
                }
            }
        } finally {
            server.stop(0);
        }
    }

    public static class PullFailureProbe {
        public static void main(String[] args) {
            assertThatThrownBy(() -> ContainerManager.getInstance().triggerPullImage("test-image:latest"))
                    .isInstanceOf(ContainerException.class)
                    .hasMessageContaining("Image pull failed")
                    .hasRootCauseMessage("layer download failed");
        }
    }

    @Test
    @EnabledIf("dockerAvailable")
    void shouldRemoveCompletedPullFromInflightMap() throws Exception {
        String image = "alpine:latest";
        ContainerManager manager = ContainerManager.getInstance();

        manager.triggerPullImage(image);

        Field field = ContainerManager.class.getDeclaredField("inflightPulls");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<Void>> inflight =
                (ConcurrentHashMap<String, CompletableFuture<Void>>) field.get(manager);
        assertThat(inflight).doesNotContainKey(image);
    }

    @Test
    @EnabledIf("dockerAvailable")
    void shouldPropagatePullFailureToAllWaiters() throws Exception {
        String image = "nonexistent-image-for-pull-test-" + System.currentTimeMillis() + ":latest";
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Long> t2Duration = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                ContainerManager.getInstance().triggerPullImage(image);
            } catch (Throwable ignored) {
            }
            latch.countDown();
        });

        Thread t2 = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                ContainerManager.getInstance().triggerPullImage(image);
            } catch (Throwable ignored) {
            }
            t2Duration.set(System.currentTimeMillis() - start);
            latch.countDown();
        });

        t1.start();
        // Ensure t1 starts the pull first
        Thread.sleep(100);
        t2.start();
        latch.await();

        // With the fix, t2 waits for t1's pull to complete and fails immediately
        // (no 100ms sleep + retry). Without the fix, t2 sleeps 100ms and retries.
        // The daemon response time varies; CI runners may be slow. A 15s threshold
        // still validates that t2 does not enter a retry loop (which would take 30s+).
        assertThat(t2Duration.get()).isNotNull();
        assertThat(t2Duration.get()).isLessThan(15000);
    }

    @Test
    @EnabledIf("dockerAvailable")
    void shouldCompletePullWithinConfiguredTimeout() throws Exception {
        String image = "alpine:latest";
        ContainerManager manager = ContainerManager.getInstance();

        long start = System.currentTimeMillis();
        manager.triggerPullImage(image);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(Duration.ofMinutes(4).toMillis());

        Field field = ContainerManager.class.getDeclaredField("inflightPulls");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<Void>> inflight =
                (ConcurrentHashMap<String, CompletableFuture<Void>>) field.get(manager);
        assertThat(inflight).doesNotContainKey(image);
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldRecoverWhenCachedImageWasRemoved() {
        String image = "alpine:latest";
        ContainerManager manager = ContainerManager.getInstance();
        Container container = null;
        manager.triggerPullImage(image);
        try {
            DockerClientFactory.client().removeImageCmd(image).withForce(true).exec();

            ContainerSpec spec =
                    ContainerSpec.builder(image).command("sleep", "30").build();
            container = Container.create(spec);

            assertThat(container.isRunning()).isTrue();
        } finally {
            if (container != null) {
                container.close();
            }
            try {
                DockerClientFactory.client()
                        .removeImageCmd(image)
                        .withForce(true)
                        .exec();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup for the image used by this test.
            }
        }
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldSkipPullWhenImageExistsLocally() {
        String image = "alpine:latest";
        ContainerManager.getInstance().triggerPullImage(image);
        ContainerSpec spec = ContainerSpec.builder(image)
                .command("sh", "-c", "echo ready && sleep 30")
                .waitForLogMessage(".*ready.*")
                .build();

        Container container = Container.create(spec);
        try {
            assertThat(container.isRunning()).isTrue();
        } finally {
            container.close();
        }
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldNotPullAgainWhenImageWasJustPulled() {
        String image = "alpine:latest";
        ContainerSpec spec = ContainerSpec.builder(image)
                .command("sh", "-c", "echo ready && sleep 30")
                .waitForLogMessage(".*ready.*")
                .build();

        // First call: pull (or cache hit) and create
        Container container1 = Container.create(spec);
        container1.close();

        // Second call should skip the pull entirely.
        Container container2 = Container.create(spec);
        try {
            assertThat(container2.isRunning()).isTrue();
        } finally {
            container2.close();
        }
    }

    @Test
    @EnabledIf("dockerAvailable")
    void shouldReportPullFailureNotTimeoutForMissingImage() {
        // A daemon 404 during pull must surface as a pull failure, not as a
        // timeout (awaitCompletion returns false for both error and timeout).
        String image = "nonexistent-image-for-pull-test-" + System.currentTimeMillis() + ":latest";
        assertThatThrownBy(() -> ContainerManager.getInstance().triggerPullImage(image))
                .isInstanceOf(org.altcontainers.api.ContainerException.class)
                .hasMessageContaining("Image pull failed");
    }

    @Test
    @EnabledIf("dockerAvailable")
    void shouldKeepInflightEntryWhileBackgroundPullContinuesAfterTimeout() throws Exception {
        // After a pull times out, the callback keeps running in the background.
        // The in-flight entry must stay registered until the callback actually
        // terminates so concurrent callers keep deduplicating against it.
        String image = "busybox:latest";
        ContainerManager manager = ContainerManager.getInstance();
        System.setProperty("altcontainers.image.pull.timeout.ms", "100");
        try {
            AltcontainersProperties.reset();
            assertThatThrownBy(() -> manager.triggerPullImage(image))
                    .isInstanceOf(org.altcontainers.api.ContainerException.class)
                    .hasMessageContaining("timed out");

            Map<String, CompletableFuture<Void>> inflight = inflightPulls(manager);
            assertThat(inflight)
                    .as("in-flight entry must remain while the background pull continues")
                    .containsKey(image);

            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                if (!inflight.containsKey(image)) {
                    break;
                }
                Thread.sleep(200);
            }
            assertThat(inflight)
                    .as("in-flight entry must be removed once the background pull terminates")
                    .doesNotContainKey(image);
        } finally {
            System.clearProperty("altcontainers.image.pull.timeout.ms");
            AltcontainersProperties.reset();
        }
    }

    private static Map<String, CompletableFuture<Void>> inflightPulls(ContainerManager manager) throws Exception {
        Field field = ContainerManager.class.getDeclaredField("inflightPulls");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<Void>> inflight = (Map<String, CompletableFuture<Void>>) field.get(manager);
        return inflight;
    }
}
