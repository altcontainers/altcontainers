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

import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session tracking state held by the global reaper daemon.
 *
 * @param sessionId the session UUID
 * @param heartbeatTimeoutMs how long the reaper waits after the last heartbeat before cleanup
 * @param lastHeartbeatNanos monotonic timestamp of the last received heartbeat
 * @param connection the TCP connection for this session, or {@code null} if closed
 */
record SessionState(String sessionId, long heartbeatTimeoutMs, AtomicLong lastHeartbeatNanos, Socket connection) {

    /**
     * Compact canonical constructor that validates the resolved fields.
     *
     * @param sessionId the session UUID; must not be blank
     * @param heartbeatTimeoutMs heartbeat timeout; must be positive
     * @param lastHeartbeatNanos last heartbeat timestamp; must not be {@code null}
     * @param connection the TCP connection; may be {@code null}
     */
    SessionState {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (heartbeatTimeoutMs <= 0) {
            throw new IllegalArgumentException("heartbeatTimeoutMs must be positive, was " + heartbeatTimeoutMs);
        }
        Objects.requireNonNull(lastHeartbeatNanos, "lastHeartbeatNanos must not be null");
    }
}
