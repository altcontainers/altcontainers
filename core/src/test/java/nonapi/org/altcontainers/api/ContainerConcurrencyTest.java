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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Verifies concurrent container destruction does not timeout.
 */
class ContainerConcurrencyTest {

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

    static boolean reaperJarAvailable() {
        return ContainerConcurrencyTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    static boolean dockerAndReaperAvailable() {
        return dockerAvailable() && reaperJarAvailable();
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void destroyContainersConcurrentlyShouldNotTimeout() throws Exception {
        int count = 20;
        List<Container> containers = new ArrayList<>();
        try {
            // Create containers
            for (int i = 0; i < count; i++) {
                ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                        .command("sleep", "300")
                        .build();
                containers.add(Container.create(spec));
            }

            // Destroy simultaneously from multiple threads
            ExecutorService executor = Executors.newFixedThreadPool(count);
            List<Future<?>> futures = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            for (Container c : containers) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        c.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            latch.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
        } finally {
            // Best-effort cleanup
            for (Container c : containers) {
                Container.close(c);
            }
        }
    }
}
