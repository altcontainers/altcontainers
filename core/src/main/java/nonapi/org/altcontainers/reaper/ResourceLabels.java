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

/**
 * Docker label keys and static helpers used by the resource reaper.
 *
 * <p>Every altcontainers-created container and network is stamped with these labels so the reaper can
 * identify and clean up orphaned resources. All methods are static; this class carries no mutable state.
 *
 * <h2>Label Scheme</h2>
 *
 * <ul>
 *   <li>{@code altcontainers-containers.managed=true} — marks a resource as managed by altcontainers.
 *       <b>Safety boundary:</b> resources without this label are never deleted by the reaper.
 *   <li>{@code altcontainers-containers.session-id=<uuid>} — identifies the test session that owns
 *       the resource.
 *   <li>{@code altcontainers-containers.createdAtMilliseconds=<epoch-ms>} — creation timestamp used
 *       for diagnostics.
 * </ul>
 */
final class ResourceLabels {

    /**
     * Label key indicating a resource is managed by altcontainers.
     */
    static final String MANAGED = "altcontainers-containers.managed";

    /**
     * Label key for the owning session UUID.
     */
    static final String SESSION_ID = "altcontainers-containers.session-id";

    /**
     * Label key for the resource creation timestamp (epoch millis).
     */
    static final String CREATED_AT_MS = "altcontainers-containers.createdAtMilliseconds";

    /**
     * Private constructor; utility class.
     */
    private ResourceLabels() {}

    /**
     * Builds a label filter map that matches all managed resources for the given session.
     *
     * @param sessionId the session UUID
     * @return an unmodifiable map with {@code altcontainers-containers.managed=true} and
     *     {@code altcontainers-containers.session-id=<sessionId>}
     */
    static Map<String, String> filterForSession(String sessionId) {
        return Map.of(MANAGED, "true", SESSION_ID, sessionId);
    }

    /**
     * Builds a label filter map that matches all Altcontainers-managed resources regardless of session.
     *
     * @return an unmodifiable map with {@code altcontainers-containers.managed=true}
     */
    static Map<String, String> filterForManaged() {
        return Map.of(MANAGED, "true");
    }

    /**
     * Extracts the session UUID from a label map.
     *
     * @param labels the resource labels
     * @return the session UUID, or {@code null} if absent
     */
    static String sessionId(Map<String, String> labels) {
        return labels.get(SESSION_ID);
    }

    /**
     * Extracts the resource creation timestamp from a label map.
     *
     * @param labels the resource labels
     * @return the creation timestamp in epoch millis, or -1 if absent or unparseable
     */
    static long createdAtMs(Map<String, String> labels) {
        return parseLongOrNegative(labels.get(CREATED_AT_MS));
    }

    /**
     * Parses a string to a long, returning -1 if the value is null or not a valid long.
     *
     * @param value the string to parse, or {@code null}
     * @return the parsed long, or -1
     */
    private static long parseLongOrNegative(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
