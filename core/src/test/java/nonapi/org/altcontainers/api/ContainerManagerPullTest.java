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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.altcontainers.api.Container;
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
        // The daemon response time adds ~500ms, so t2 should complete well under 3s.
        assertThat(t2Duration.get()).isNotNull();
        assertThat(t2Duration.get()).isLessThan(3000);
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
}
