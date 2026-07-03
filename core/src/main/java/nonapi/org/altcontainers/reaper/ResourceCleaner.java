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

import static nonapi.org.altcontainers.ContainerOperations.*;
import static nonapi.org.altcontainers.NetworkOperations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import nonapi.org.altcontainers.DockerClient;
import nonapi.org.altcontainers.PollBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless helper that executes label-authoritative session cleanup.
 *
 * <p>Deletion authority is the resource labels, not registration: only resources carrying both
 * {@code altcontainers-containers.managed} and {@code altcontainers-containers.session-id=<sessionId>} are
 * removed.
 *
 * <h2>Cleanup Order</h2>
 *
 * <ol>
 *   <li>Containers (by label filter)
 *   <li>Networks (by label filter)
 * </ol>
 */
public final class ResourceCleaner {

    private static final Logger logger = LoggerFactory.getLogger(ResourceCleaner.class);

    /**
     * Private constructor; utility class.
     */
    private ResourceCleaner() {
        // Intentionally empty
    }

    /**
     * Force-removes all containers and networks belonging to the given session.
     *
     * <p>Cleanup order: containers then networks. Each resource type is fully cleaned before proceeding
     * to the next. Transient failures are retried with backoff, bounded by the cleanup timeout.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param cleanupTimeoutMs the overall cleanup deadline in milliseconds; must be positive
     * @throws IllegalArgumentException if {@code cleanupTimeoutMs} is not positive
     */
    public static void cleanupSession(DockerClient client, String sessionId, long cleanupTimeoutMs) {
        if (cleanupTimeoutMs <= 0) {
            throw new IllegalArgumentException("cleanupTimeoutMs must be positive, was " + cleanupTimeoutMs);
        }
        long deadlineNanos = System.nanoTime() + cleanupTimeoutMs * 1_000_000L;
        Map<String, String> sessionFilter = ResourceLabels.filterForSession(sessionId);

        // 1. Containers.
        cleanupContainers(client, sessionFilter, deadlineNanos);

        // 2. Networks.
        cleanupNetworks(client, sessionFilter, deadlineNanos);
    }

    /**
     * Removes all containers matching the given label filter, retrying transient failures.
     *
     * @param client the Docker client
     * @param filter the label filter
     * @param deadlineNanos the cleanup deadline
     */
    private static void cleanupContainers(DockerClient client, Map<String, String> filter, long deadlineNanos) {
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        while (true) {
            List<String> containerIds = listSafely(() -> listContainerIdsByLabels(client, filter));
            if (containerIds.isEmpty()) {
                return;
            }
            for (String id : containerIds) {
                try {
                    forceRemoveContainer(client, id);
                    logger.info("Removed container " + id);
                } catch (RuntimeException e) {
                    logger.error("Failed to remove container " + id + ": " + e.getMessage());
                }
            }
            if (!PollBackoff.sleepWithBackoff(deadlineNanos, sleepMs, 0)) {
                // Final re-check: list once more after the deadline has elapsed
                // to catch resources that appeared during the final sleep.
                List<String> remaining = listSafely(() -> listContainerIdsByLabels(client, filter));
                if (remaining.isEmpty()) {
                    return;
                }
                for (String id : remaining) {
                    try {
                        forceRemoveContainer(client, id);
                        logger.info("Removed container " + id);
                    } catch (RuntimeException e) {
                        logger.error("Failed to remove container (post-deadline) " + id + ": " + e.getMessage());
                    }
                }
                return;
            }
        }
    }

    /**
     * Removes all networks matching the given label filter, retrying transient failures.
     *
     * @param client the Docker client
     * @param filter the label filter
     * @param deadlineNanos the cleanup deadline
     */
    private static void cleanupNetworks(DockerClient client, Map<String, String> filter, long deadlineNanos) {
        AtomicLong sleepMs = new AtomicLong(PollBackoff.INITIAL_INTERVAL_MS);
        while (true) {
            List<String> networkIds = listSafely(() -> listNetworkIdsByLabels(client, filter));
            if (networkIds.isEmpty()) {
                return;
            }
            for (String id : networkIds) {
                try {
                    removeNetwork(client, id);
                    logger.info("Removed network " + id);
                } catch (RuntimeException e) {
                    logger.error("Failed to remove network " + id + ": " + e.getMessage());
                }
            }
            if (!PollBackoff.sleepWithBackoff(deadlineNanos, sleepMs, 0)) {
                // Final re-check: list once more after the deadline has elapsed
                // to catch resources that appeared during the final sleep.
                List<String> remaining = listSafely(() -> listNetworkIdsByLabels(client, filter));
                if (remaining.isEmpty()) {
                    return;
                }
                for (String id : remaining) {
                    try {
                        removeNetwork(client, id);
                        logger.info("Removed network " + id);
                    } catch (RuntimeException e) {
                        logger.error("Failed to remove network (post-deadline) " + id + ": " + e.getMessage());
                    }
                }
                return;
            }
        }
    }

    /**
     * Executes a list operation safely, returning an empty list on failure.
     *
     * @param listOp the list operation
     * @return the list result, or an empty list on failure
     */
    private static List<String> listSafely(ListOperation listOp) {
        try {
            return listOp.execute();
        } catch (RuntimeException e) {
            logger.error("List operation failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Functional interface for list operations that may throw.
     */
    @FunctionalInterface
    private interface ListOperation {
        List<String> execute();
    }
}
