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

/**
 * Polling helpers shared by reaper lifecycle tests.
 */
final class ReaperTestSupport {

    private static final long PORT_POLL_INTERVAL_MS = 100L;

    private ReaperTestSupport() {
        // Intentionally empty
    }

    /**
     * Polls the reaper port discovery file until a port appears or the timeout
     * expires.
     *
     * @param sessionId the session UUID
     * @param timeoutMs the maximum time to wait in milliseconds
     * @return the port, or {@code null} if the file did not appear in time
     * @throws InterruptedException if the current thread is interrupted
     */
    static Integer waitForPort(String sessionId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Integer port = null;
        while (System.currentTimeMillis() < deadline && port == null) {
            port = ReaperDiscovery.readPort(sessionId).orElse(null);
            if (port == null) {
                Thread.sleep(PORT_POLL_INTERVAL_MS);
            }
        }
        return port;
    }
}
