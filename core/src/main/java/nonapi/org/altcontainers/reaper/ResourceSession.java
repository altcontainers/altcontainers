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

import java.util.Map;
import java.util.UUID;

/**
 * Per-parent-JVM session state shared with the resource reaper.
 *
 * <p>A {@code ResourceSession} captures a unique session UUID so the reaper can associate labeled
 * Docker resources with a specific parent JVM. The session UUID is fresh per JVM, not per resource.
 *
 * <p>{@link #labelsForNewResource()} returns a label map suitable for attaching to new containers
 * and networks. The {@code createdAtMilliseconds} value is fresh on every call.
 */
public final class ResourceSession {

    /**
     * Unique session identifier.
     */
    private final String sessionId;

    /**
     * Creates a new session.
     */
    public ResourceSession() {
        this.sessionId = UUID.randomUUID().toString();
    }

    /**
     * Returns the unique session identifier.
     *
     * @return the session UUID string
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns a label map suitable for a new container or network.
     *
     * <p>The returned map contains three entries:
     *
     * <ul>
     *   <li>{@code altcontainers-containers.managed} = "true"
     *   <li>{@code altcontainers-containers.session-id} = this session's UUID
     *   <li>{@code altcontainers-containers.createdAtMilliseconds} = {@code System.currentTimeMillis()}
     * </ul>
     *
     * <p>The {@code createdAtMilliseconds} value is fresh on every call, so each resource gets a
     * unique creation timestamp.
     *
     * @return an unmodifiable map of label key-value pairs
     */
    public Map<String, String> labelsForNewResource() {
        return Map.of(
                ResourceLabels.MANAGED,
                "true",
                ResourceLabels.SESSION_ID,
                sessionId,
                ResourceLabels.CREATED_AT_MS,
                String.valueOf(System.currentTimeMillis()));
    }
}
