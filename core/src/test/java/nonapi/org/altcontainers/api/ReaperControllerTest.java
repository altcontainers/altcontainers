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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReaperController} concurrency and lifecycle.
 */
class ReaperControllerTest {

    @BeforeEach
    void setUp() throws Exception {
        System.clearProperty("altcontainers.reaper.disabled");
        AltcontainersProperties.reset();
        resetSingleton();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("altcontainers.reaper.disabled");
        AltcontainersProperties.reset();
        resetSingleton();
    }

    @Test
    void ensureReadyShouldNotDeadlockUnderConcurrentAccess() throws Exception {
        System.setProperty("altcontainers.reaper.disabled", "true");
        AltcontainersProperties.reset();

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<ResourceSession>> tasks = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> ReaperController.instance().ensureReady());
            }

            List<Future<ResourceSession>> futures = executor.invokeAll(tasks, 5, TimeUnit.SECONDS);

            String sessionId = null;
            for (Future<ResourceSession> future : futures) {
                assertThat(future.isDone()).isTrue();
                ResourceSession session = future.get(1, TimeUnit.SECONDS);
                assertThat(session).isNotNull();
                assertThat(session.sessionId()).isNotNull();
                if (sessionId == null) {
                    sessionId = session.sessionId();
                } else {
                    assertThat(session.sessionId()).isEqualTo(sessionId);
                }
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void ensureReadyShouldLogDisabledMessageOnce() throws Exception {
        System.setProperty("altcontainers.reaper.disabled", "true");
        AltcontainersProperties.reset();

        ReaperController ctrl = ReaperController.instance();

        // First call should log the message.
        ResourceSession first = ctrl.ensureReady();
        assertThat(first).isNotNull();
        assertThat(first.sessionId()).isNotNull();
        assertThat(ctrl.disabledLoggedForTesting()).isTrue();

        // Second call should return the same session and NOT re-log.
        ResourceSession second = ctrl.ensureReady();
        assertThat(second).isSameAs(first);
        assertThat(ctrl.disabledLoggedForTesting()).isTrue();

        // Third call — same.
        ResourceSession third = ctrl.ensureReady();
        assertThat(third).isSameAs(first);
        assertThat(ctrl.disabledLoggedForTesting()).isTrue();
    }

    private static void resetSingleton() throws Exception {
        Field field = ReaperController.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
