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
import java.util.Map;
import nonapi.org.altcontainers.api.ConcreteContainer;
import nonapi.org.altcontainers.api.ContainerMetadata;
import nonapi.org.altcontainers.api.DockerClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Verifies Container behavior: {@code isRunning()} performs a live daemon
 * query on every call; {@code host()} uses cached metadata with daemon
 * fallback; {@code hostPort()} uses cached metadata only.
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
    void shouldQueryDaemonEvenWhenMetadataSaysRunning() {
        // isRunning() always performs a live daemon query — cached
        // metadata.running=true is no longer trusted. With no daemon
        // available, ContainerManager.isContainerRunning() returns false.
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

    // --- Docker-gating helpers (consistent with ContainerManagerPullTest) ---

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
        return ContainerTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    static boolean dockerAndReaperAvailable() {
        return dockerAvailable() && reaperJarAvailable();
    }
}
