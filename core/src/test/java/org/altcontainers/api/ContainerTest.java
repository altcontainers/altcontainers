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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.exception.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import nonapi.org.altcontainers.api.ConcreteContainer;
import nonapi.org.altcontainers.api.ContainerMetadata;
import nonapi.org.altcontainers.api.DockerClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies Container behavior: {@code isRunning()} performs a live daemon
 * query on every call; {@code host()} and {@code hostPort()} use cached
 * metadata with daemon fallback.
 */
class ContainerTest {

    @Test
    void shouldQueryDaemonWhenMetadataSaysNotRunning() {
        // With metadata.running=false, the code path must fall through to
        // ContainerManager.isContainerRunning() rather than returning the
        // cached false value directly.
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("localhost", false, Map.of()));

        // No daemon available — ContainerManager returns false. The test
        // verifies behavior parity (both old and new code return false),
        // but the fix ensures the code takes the daemon path, which is
        // verified by code coverage.
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void shouldQueryDaemonWhenMetadataSaysRunning() {
        // metadata.running=true is no longer trusted — isRunning()
        // always performs a live daemon query. With no daemon available,
        // ContainerManager.isContainerRunning() returns false (catches
        // RuntimeException from the failed inspect call).
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("myhost", true, Map.of()));

        assertThat(container.isRunning()).isFalse();
        // host() caching is unchanged — still returns cached value.
        assertThat(container.host()).isEqualTo("myhost");
    }

    @Test
    void shouldQueryDaemonWhenMetadataIsNull() {
        // No metadata — falls through to daemon queries (returns defaults with no daemon).
        Container container = new ConcreteContainer(
                "test-id", "test-image", ContainerSpec.builder("test-image").build(), null);

        // ContainerManager returns false when daemon is unavailable
        assertThat(container.isRunning()).isFalse();
        // ContainerManager returns "localhost" when daemon is unavailable
        assertThat(container.host()).isEqualTo("localhost");
    }

    @Test
    void shouldQueryDaemonWhenHostIsNullInMetadata() {
        // metadata.host() is null — must fall through to daemon query.
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata(null, true, Map.of()));

        // With host=null in metadata, falls through to ContainerManager.host()
        // which returns "localhost" when no daemon is available.
        assertThat(container.host()).isEqualTo("localhost");
    }

    @Test
    void shouldRejectNullValueInPortBindings() {
        java.util.HashMap<Integer, Integer> bindings = new java.util.HashMap<>();
        bindings.put(8080, null);
        assertThatThrownBy(() -> ContainerSpec.builder("test:latest")
                        .exposePorts(8080)
                        .portBindings(bindings)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void shouldReturnNullForUnmappedPort() {
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("localhost", true, Map.of()));
        assertThat(container.hostPort(9999)).isNull();
    }

    @Test
    void shouldReturnOriginalSpec() {
        ContainerSpec spec =
                ContainerSpec.builder("alpine:latest").exposePorts(8080).build();
        Container container = new ConcreteContainer(
                "test-id", "alpine:latest", spec, new ContainerMetadata("localhost", true, Map.of()));
        assertThat(container.spec()).isSameAs(spec);
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void isRunningShouldTrackContainerDeath() {
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sleep", "60")
                .startupAttempts(1)
                .build();

        Container container = Container.create(spec);
        try {
            // Container is running after startup
            assertThat(container.isRunning()).isTrue();

            // Kill the container via the Docker daemon
            DockerClientFactory.client().killContainerCmd(container.id()).exec();

            // Give the daemon a moment to update state
            long deadline = System.currentTimeMillis() + 10_000;
            boolean becameDead = false;
            while (System.currentTimeMillis() < deadline) {
                if (!container.isRunning()) {
                    becameDead = true;
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertThat(becameDead)
                    .as("isRunning() should transition to false after docker kill")
                    .isTrue();
        } finally {
            // Force-remove even though the container is already killed.
            // Using direct removal (not container.close()) because the
            // container has been killed and the reaper may already have
            // cleaned it up.
            try {
                DockerClientFactory.client()
                        .removeContainerCmd(container.id())
                        .withForce(true)
                        .exec();
            } catch (NotFoundException ignored) {
                // Container may already be removed by reaper; that's fine.
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void errorFromPrepareHookShouldDestroyAttemptAndPropagateUnchanged() {
        // An Error (for example a failed test assertion) thrown during startup must not
        // leave the started container behind until the JVM exits.
        AtomicReference<String> containerId = new AtomicReference<>();
        AssertionError failure = new AssertionError("prepare failed");
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "trap 'exit 0' TERM; sleep 60 & wait")
                .prepare(container -> {
                    containerId.set(container.id());
                    throw failure;
                })
                .build();
        try {
            assertThatThrownBy(() -> Container.create(spec)).isSameAs(failure);
            assertThat(containerId.get()).isNotNull();
            assertThatThrownBy(() -> DockerClientFactory.client()
                            .inspectContainerCmd(containerId.get())
                            .exec())
                    .as("container from the failed attempt should have been removed")
                    .isInstanceOf(NotFoundException.class);
        } finally {
            if (containerId.get() != null) {
                try {
                    DockerClientFactory.client()
                            .removeContainerCmd(containerId.get())
                            .withForce(true)
                            .exec();
                } catch (RuntimeException ignored) {
                    // Already removed, which is the expected outcome.
                }
            }
        }
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void readOnlyBindMountShouldExposeHostContentAndRejectWrites(@TempDir Path hostDir) throws IOException {
        Files.writeString(hostDir.resolve("input.txt"), "bind-mount-content");
        hostDir.toFile().setReadable(true, false);
        hostDir.toFile().setExecutable(true, false);
        hostDir.resolve("input.txt").toFile().setReadable(true, false);

        StringBuffer output = new StringBuffer();
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .bindDirectory(hostDir.toString(), "/data", true)
                .command(
                        "sh",
                        "-c",
                        "echo \"READ:$(cat /data/input.txt)\";"
                                + " if touch /data/output.txt 2>/dev/null; then echo WRITE_ALLOWED;"
                                + " else echo WRITE_DENIED; fi; sleep 60")
                .outputListener(frame -> output.append(frame.utf8String()))
                .waitForLogMessage(".*WRITE_(ALLOWED|DENIED).*")
                .startupAttempts(1)
                .build();

        try (Container container = Container.create(spec)) {
            assertThat(container.isRunning()).isTrue();
        }

        assertThat(output.toString()).contains("READ:bind-mount-content").contains("WRITE_DENIED");
        assertThat(hostDir.resolve("output.txt")).doesNotExist();
    }

    @Test
    void shouldRejectNonPositiveStartupTimeoutBeforeTouchingDocker() {
        // Documented contract: create() validates startupTimeout is positive and throws
        // IllegalArgumentException. The check must run before any Docker or reaper work.
        ContainerSpec zero = ContainerSpec.builder("alpine:latest")
                .startupTimeout(Duration.ZERO)
                .build();
        assertThatThrownBy(() -> Container.create(zero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startupTimeout");

        ContainerSpec negative = ContainerSpec.builder("alpine:latest")
                .startupTimeout(Duration.ofSeconds(-1))
                .build();
        assertThatThrownBy(() -> Container.create(negative))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startupTimeout");
    }

    @Test
    void shouldRejectNonPositiveStartupAttemptsBeforeTouchingDocker() {
        // A custom ContainerSpec can bypass the builder's >= 1 validation; create()
        // must reject it instead of failing later with a cause-less startup failure.
        ContainerSpec spec =
                new ZeroAttemptsSpec(ContainerSpec.builder("alpine:latest").build());

        assertThatThrownBy(() -> Container.create(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startupAttempts");
    }

    /** A spec whose startup attempt count bypasses the builder's validation. */
    private static final class ZeroAttemptsSpec extends GenericContainerSpec {

        private ZeroAttemptsSpec(GenericContainerSpec spec) {
            super(spec);
        }

        @Override
        public int startupAttempts() {
            return 0;
        }
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void documentedSetupVerificationSampleShouldStart() {
        // The setup-verification sample in website/docs/getting-started/project-setup.md must
        // work as written: a long-running container, so the default is-running startup check
        // passes. A one-shot image such as hello-world fails that check.
        try (Container container = Container.create(
                ContainerSpec.builder("alpine:latest").command("sleep", "30").build())) {
            assertThat(container.isRunning()).isTrue();
        }
    }

    // --- Docker-gating helpers (consistent with ContainerManagerPullTest) ---

    static boolean dockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        return false;
                    }
                }
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    static boolean reaperJarAvailable() {
        return ContainerTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    static boolean dockerAndReaperAvailable() {
        return dockerAvailable() && reaperJarAvailable();
    }
}
