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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Discovery file I/O for the reaper's port.
 */
final class ReaperDiscovery {

    private ReaperDiscovery() {
        // Intentionally empty
    }

    /**
     * Returns the port file path for a session.
     *
     * @param sessionId the session UUID
     * @return the port file path
     */
    static Path portFilePath(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".port");
    }

    /**
     * Reads the reaper port from the discovery file.
     *
     * @param sessionId the session UUID
     * @return the port, or empty if the file is missing or unreadable
     */
    static Optional<Integer> readPort(String sessionId) {
        Path path = portFilePath(sessionId);
        if (!Files.isReadable(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            return Optional.of(Integer.parseInt(content));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
