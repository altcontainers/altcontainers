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

package org.altcontainers.reaper;

/**
 * Immutable task representing a single Docker resource to be cleaned up.
 *
 * @param id the Docker resource ID (container ID or network ID)
 * @param type the type of Docker resource
 * @param attempts the number of failed cleanup attempts so far
 */
record CleanupTask(String id, CleanupTask.ResourceType type, int attempts) {

    /**
     * The type of Docker resource to clean up.
     */
    enum ResourceType {
        /** A Docker container. */
        CONTAINER,
        /** A Docker network. */
        NETWORK
    }

    /**
     * Returns a new task with the same id and type but with an updated attempt count.
     *
     * @param newAttempts the new attempt count
     * @return a new {@code CleanupTask} with the updated attempt count
     */
    CleanupTask withAttempts(int newAttempts) {
        return new CleanupTask(id, type, newAttempts);
    }
}
