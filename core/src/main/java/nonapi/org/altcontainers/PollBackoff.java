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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exponential backoff sleep helper extracted from {@link DockerClient#destroyAndAwait destroyAndAwait}.
 *
 * <p>Sleeps with exponential backoff from a caller-provided base millis (initially 200ms), doubling each
 * call up to a max of 2000ms, with ±50ms jitter. Sleep is capped by the remaining deadline. Returns
 * {@code false} when the deadline has passed or the thread is interrupted.
 *
 * <p>This class is stateless from the caller's perspective — the backoff multiplier is stored in the
 * caller-provided {@link AtomicLong}, so multiple cleanup cycles can share the same backoff state or
 * reset it.
 */
public final class PollBackoff {

    /**
     * Initial poll interval in milliseconds.
     */
    public static final long INITIAL_INTERVAL_MS = 200L;

    /**
     * Maximum poll interval cap in milliseconds.
     */
    public static final long MAX_INTERVAL_MS = 2000L;

    /**
     * Random jitter range (±milliseconds).
     */
    private static final long JITTER_MS = 50L;

    /**
     * Private constructor; utility class.
     */
    private PollBackoff() {}

    /**
     * Sleeps with exponential backoff, bounded by the given deadline.
     *
     * <p>On each call, reads the current sleep duration from {@code sleepMsRef}, adds ±50ms jitter, caps
     * at the remaining deadline, and sleeps. After sleeping, doubles {@code sleepMsRef} (capped at 2000ms).
     *
     * @param deadlineNanos the monotonic deadline (from {@link System#nanoTime()}) after which sleep is
     *     skipped
     * @param sleepMsRef reference to the current backoff sleep duration; updated in-place (doubled after
     *     each successful sleep)
     * @param elapsedNanos the time already spent in the current iteration (e.g., probe time), subtracted
     *     from the sleep duration to maintain timing accuracy
     * @return {@code true} if the sleep completed within the deadline; {@code false} if the deadline
     *     passed or the thread was interrupted
     */
    public static boolean sleepWithBackoff(long deadlineNanos, AtomicLong sleepMsRef, long elapsedNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return false;
        }
        long sleepMs = sleepMsRef.get();
        long jitter = ThreadLocalRandom.current().nextLong(-JITTER_MS, JITTER_MS + 1);
        long effectiveMs = Math.max(1, sleepMs + jitter - elapsedNanos / 1_000_000L);
        effectiveMs = Math.min(effectiveMs, remainingNanos / 1_000_000L);
        try {
            Thread.sleep(effectiveMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        sleepMsRef.updateAndGet(s -> Math.min(s * 2, MAX_INTERVAL_MS));
        return true;
    }
}
