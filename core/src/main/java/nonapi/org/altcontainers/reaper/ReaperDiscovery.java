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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.altcontainers.api.Version;

/**
 * Discovery file utility for the global reaper daemon.
 *
 * <p>Writes and reads a versioned discovery file at
 * {@code <base>/.altcontainers/daemon/<version>/reaper.port}.
 * The file contains {@code <port> <pid>} on a single line.
 */
final class ReaperDiscovery {

    private ReaperDiscovery() {}

    /**
     * Returns the versioned discovery file path.
     *
     * <p>Uses {@code user.home} as the base directory, falling back to {@code java.io.tmpdir}
     * if {@code user.home} is unavailable.
     *
     * @return the discovery file path
     */
    static Path discoveryPath() {
        String base = System.getProperty("user.home");
        if (base == null || base.isBlank()) {
            base = System.getProperty("java.io.tmpdir");
        }
        return Paths.get(base, ".altcontainers", "daemon", Version.version(), "reaper.port");
    }

    /**
     * Writes {@code <port> <pid>} to the discovery file, creating parent directories as needed.
     *
     * @param port the listening port
     * @throws IOException if the file cannot be written
     */
    static void writePort(int port) throws IOException {
        Path path = discoveryPath();
        Files.createDirectories(path.getParent());
        String content = port + " " + ProcessHandle.current().pid();
        Files.writeString(
                path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Deletes the discovery file if it exists.
     *
     * <p>Best-effort: any failure is silently ignored.
     */
    static void delete() {
        try {
            Files.deleteIfExists(discoveryPath());
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /**
     * Reads the discovery file and returns the parsed entry.
     *
     * @return the discovery entry, or {@link Optional#empty()} if the file is absent,
     *     unreadable, or malformed
     */
    static Optional<DiscoveryEntry> read() {
        Path path = discoveryPath();
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return Optional.empty();
        }
        String[] parts = content.split(" ");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(parts[0]);
            long pid = Long.parseLong(parts[1]);
            return Optional.of(new DiscoveryEntry(port, pid));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Checks whether the process identified by the discovery entry is still alive.
     *
     * @param entry the discovery entry
     * @return {@code true} if the process is alive
     */
    static boolean isAlive(DiscoveryEntry entry) {
        return ProcessHandle.of(entry.pid()).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * A parsed discovery file entry.
     *
     * @param port the reaper listening port
     * @param pid the reaper process ID
     */
    record DiscoveryEntry(int port, long pid) {}
}
