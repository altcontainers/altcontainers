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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import nonapi.org.altcontainers.docker.DockerNotFoundException;
import org.altcontainers.api.ContainerException;

/**
 * Shared polling infrastructure for container and network destruction.
 *
 * <p>Package-private static utility. Used by {@link DockerClient} composite destroy methods and
 * by {@link ContainerOperations} and {@link NetworkOperations} await-until-gone methods.
 */
public final class DestructionPoller {

    private DestructionPoller() {
        // Intentionally empty
    }

    public enum Presence {
        GONE,
        PRESENT
    }

    public static void destroyAndAwait(String description, Duration timeout, Supplier<Presence> probe) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        while (true) {
            long iterationStartNanos = System.nanoTime();
            if (probe.get() == Presence.GONE) {
                return;
            }
            long elapsedNanos = System.nanoTime() - iterationStartNanos;
            if (!PollBackoff.sleepWithBackoff(deadlineNanos, sleepMs, elapsedNanos)) {
                // Re-check condition before reporting timeout — resource may have been
                // destroyed during the sleep, even when the deadline has elapsed.
                if (probe.get() == Presence.GONE) {
                    return;
                }
                if (deadlineNanos - System.nanoTime() <= 0) {
                    break;
                }
                Thread.currentThread().interrupt();
                throw new ContainerException("Interrupted while destroying " + description);
            }
        }
        throw new ContainerException("Failed to confirm destruction of " + description + " within " + timeout);
    }

    public static void awaitAbsence(String description, Duration timeout, Supplier<Boolean> isAbsent) {
        destroyAndAwait(description, timeout, () -> isAbsent.get() ? Presence.GONE : Presence.PRESENT);
    }

    public static Presence removeContainerAndProbe(DockerClient client, String id) {
        try {
            client.delegate().removeContainer(id, true);
        } catch (DockerNotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            // Transient; fall through to the existence check.
        }
        try {
            client.delegate().inspectContainer(id);
            return Presence.PRESENT;
        } catch (DockerNotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            return Presence.PRESENT;
        }
    }

    public static Presence removeNetworkAndProbe(DockerClient client, String id) {
        try {
            client.delegate().removeNetwork(id);
        } catch (DockerNotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            // Transient.
        }
        try {
            client.delegate().inspectNetwork(id);
            return Presence.PRESENT;
        } catch (DockerNotFoundException e) {
            return Presence.GONE;
        } catch (RuntimeException ignored) {
            return Presence.PRESENT;
        }
    }
}
