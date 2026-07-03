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

package nonapi.org.altcontainers.reaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import nonapi.org.altcontainers.DestructionPoller;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.Test;

class ResourceCleanerTest {

    @Test
    void filterForSessionIncludesManagedAndSessionLabels() {
        assertThat(ResourceLabels.filterForSession("abc"))
                .containsEntry(ResourceLabels.MANAGED, "true")
                .containsEntry(ResourceLabels.SESSION_ID, "abc");
    }

    @Test
    void filterForManagedOnlyIncludesManagedLabel() {
        assertThat(ResourceLabels.filterForManaged())
                .containsEntry(ResourceLabels.MANAGED, "true")
                .hasSize(1);
    }

    @Test
    void rejectsZeroCleanupTimeout() {
        assertThatThrownBy(() -> ResourceCleaner.cleanupSession(null, "id", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanupTimeoutMs must be positive");
    }

    @Test
    void rejectsNegativeCleanupTimeout() {
        assertThatThrownBy(() -> ResourceCleaner.cleanupSession(null, "id", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanupTimeoutMs must be positive");
    }

    @Test
    void destroyAndAwaitObservesContainerGoneDuringFinalSleep() {
        // A probe that returns PRESENT for the first three calls and GONE starting
        // from the fourth. With PollBackoff starting at 200 ms and a 400 ms timeout,
        // iterations 1-2 each check (PRESENT) and sleep ~200 ms. On iteration 3 the
        // check returns PRESENT and sleepWithBackoff returns false immediately
        // (deadline expired). Pre-fix, the loop breaks without re-checking. Post-fix,
        // the re-check observes GONE (call 4) and the method returns normally.
        AtomicInteger callCount = new AtomicInteger(0);
        var probe = new java.util.function.Supplier<DestructionPoller.Presence>() {
            @Override
            public DestructionPoller.Presence get() {
                int count = callCount.incrementAndGet();
                return count >= 4 ? DestructionPoller.Presence.GONE : DestructionPoller.Presence.PRESENT;
            }
        };

        DestructionPoller.destroyAndAwait("test resource", Duration.ofMillis(400), probe);

        // The probe must be called four times: three top-of-loop checks plus one
        // post-sleep re-check.
        assertThat(callCount.get()).isEqualTo(4);
    }

    @Test
    void destroyAndAwaitTimesOutWhenResourceNeverGone() {
        AtomicInteger callCount = new AtomicInteger(0);
        var probe = new java.util.function.Supplier<DestructionPoller.Presence>() {
            @Override
            public DestructionPoller.Presence get() {
                callCount.incrementAndGet();
                return DestructionPoller.Presence.PRESENT;
            }
        };

        assertThatThrownBy(() -> DestructionPoller.destroyAndAwait("test resource", Duration.ofMillis(200), probe))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Failed to confirm destruction");

        // The probe is called at least once (initial check).
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
    }
}
