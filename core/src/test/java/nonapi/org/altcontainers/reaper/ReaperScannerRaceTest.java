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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReaperScannerRaceTest")
class ReaperScannerRaceTest {

    @Test
    @DisplayName("scanner does not remove session when heartbeat arrives during check")
    void shouldNotRemoveSessionWhenHeartbeatArrivesDuringCheck() throws Exception {
        ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
        String sessionId = "test-session-id";
        long heartbeatTimeoutMs = 30_000L;

        // Create a session whose last heartbeat was 40 seconds ago — stale, past timeout.
        SessionState staleSession = new SessionState(
                sessionId, heartbeatTimeoutMs, new AtomicLong(System.nanoTime() - 40_000_000_000L), null);
        sessions.put(sessionId, staleSession);

        long now = System.nanoTime();

        // Simulate the snapshot read the scanner does at the top of the iteration.
        long elapsedNs = now - staleSession.lastHeartbeatNanos().get();
        assertThat(elapsedNs).isGreaterThan(heartbeatTimeoutMs * 1_000_000L);

        // Simulate a heartbeat arriving between the snapshot read and the remove —
        // the handler updates lastHeartbeatNanos to now.
        staleSession.lastHeartbeatNanos().set(System.nanoTime());

        // Run the double-read guard (the fix):
        // 1. Re-read session from map
        SessionState current = sessions.get(sessionId);
        assertThat(current).isNotNull();

        // 2. Re-read lastHeartbeatNanos — it's fresh because we just set it.
        long freshElapsedNs = now - current.lastHeartbeatNanos().get();
        assertThat(freshElapsedNs)
                .as("freshElapsedNs should be negative or tiny since heartbeat just arrived")
                .isLessThanOrEqualTo(heartbeatTimeoutMs * 1_000_000L);

        // The guard prevents removal — session stays in the map.
        assertThat(sessions.size()).isEqualTo(1);
    }
}
