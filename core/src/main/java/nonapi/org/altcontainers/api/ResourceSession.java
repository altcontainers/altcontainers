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

import java.util.Map;
import java.util.UUID;

/**
 * Per-JVM session UUID and label generation.
 */
public final class ResourceSession {

    private final String sessionId;

    /** Creates a new session with a random UUID. */
    public ResourceSession() {
        this.sessionId = UUID.randomUUID().toString();
    }

    /**
     * Returns the session UUID.
     *
     * @return the session UUID
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns labels for a new Docker resource.
     *
     * @return label map containing the session ID
     */
    public Map<String, String> labelsForNewResource() {
        return Map.of(ResourceLabels.SESSION_ID, sessionId);
    }
}
