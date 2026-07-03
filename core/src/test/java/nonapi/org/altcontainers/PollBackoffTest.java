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

package nonapi.org.altcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PollBackoffTest {

    @Test
    void shouldSleepWithInitialInterval() {
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        long deadline = System.nanoTime() + 10_000_000_000L; // 10s
        long start = System.nanoTime();
        boolean result = PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        long elapsed = System.nanoTime() - start;
        assertThat(result).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(150_000_000L); // at least ~150ms (200 - 50 jitter)
    }

    @Test
    void shouldExponentialBackoffCappedAtMax() {
        AtomicLong sleepMs = new AtomicLong(1999L);
        long deadline = System.nanoTime() + 10_000_000_000L;
        PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        assertThat(sleepMs.get()).isEqualTo(PollBackoff.MAX_INTERVAL_MS); // capped at 2000
    }

    @Test
    void shouldReturnFalseOnDeadlinePassed() {
        AtomicLong sleepMs = new AtomicLong(5000L);
        long deadline = System.nanoTime() - 1_000_000_000L; // 1s in the past
        boolean result = PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        assertThat(result).isFalse();
        assertThat(sleepMs.get()).isEqualTo(5000L); // sleepMs unchanged (no sleep happened)
    }

    @Test
    void shouldReturnTrueWhenSleepCompletes() {
        AtomicLong sleepMs = new AtomicLong(1L);
        long deadline = System.nanoTime() + 10_000_000_000L; // 10s
        boolean result = PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        assertThat(result).isTrue();
    }

    @Test
    void shouldDoubleSleepMsAfterSleep() {
        AtomicLong sleepMs = new AtomicLong(200L);
        long deadline = System.nanoTime() + 10_000_000_000L;
        PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        assertThat(sleepMs.get()).isEqualTo(400L); // doubled
    }

    @Test
    void shouldSleepBeforeReturnFalseWhenRemainingUnderOneMillisecond() {
        AtomicLong sleepMs = new AtomicLong(1L);
        long deadline = System.nanoTime() + 500_000L; // 500µs in the future
        long start = System.nanoTime();
        boolean result = PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        long elapsed = System.nanoTime() - start;
        assertThat(result).isFalse(); // deadline passed after micro-sleep
        assertThat(elapsed).isGreaterThanOrEqualTo(400_000L); // must have slept, not spun
        assertThat(sleepMs.get()).isEqualTo(1L); // sleepMs unchanged (no backoff on <1ms)
    }

    @Test
    void shouldNotSpinWhenRemainingApproachesDeadline() {
        AtomicLong sleepMs = new AtomicLong(1L);
        long deadline = System.nanoTime() + 900_000L; // 900µs in the future
        long start = System.nanoTime();
        boolean result = PollBackoff.sleepWithBackoff(deadline, sleepMs, 0);
        long elapsed = System.nanoTime() - start;
        assertThat(result).isFalse(); // deadline passed after sleep
        assertThat(elapsed).isGreaterThanOrEqualTo(500_000L); // must have slept, not spun
    }
}
