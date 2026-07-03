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

import org.altcontainers.api.Version;

/**
 * Protocol constants and command parsing for the reaper TCP protocol.
 *
 * <h2>Protocol</h2>
 *
 * <p>Line-oriented TCP protocol. Client sends commands, server responds.
 *
 * <pre>
 * Client → Server: VERSION &lt;version&gt;
 * Server → Client: OK VERSION &lt;version&gt; &lt;version&gt;
 * Client → Server: CONNECT &lt;sessionId&gt; &lt;heartbeatTimeoutMs&gt;
 * Server → Client: OK
 * Client → Server: HEARTBEAT
 * Server → Client: OK
 * Client → Server: TERMINATE
 * Server → Client: OK
 * </pre>
 *
 * <p>Verbs are case-sensitive uppercase. Extra arguments are rejected.
 */
public final class Protocol {

    /**
     * Protocol version. Matches the Altcontainers build version from {@link Version}.
     */
    public static final String VERSION = Version.version();

    /**
     * Command verbs.
     */
    public static final String CMD_VERSION = "VERSION";

    public static final String CMD_CONNECT = "CONNECT";
    public static final String CMD_HEARTBEAT = "HEARTBEAT";

    public static final String CMD_TERMINATE = "TERMINATE";

    /**
     * Response prefixes.
     */
    public static final String RSP_OK = "OK";

    public static final String RSP_ERROR = "ERROR";

    /**
     * Private constructor; utility class.
     */
    private Protocol() {
        // Intentionally empty
    }

    /**
     * Parses a protocol line into a command.
     *
     * <p>Leading and trailing whitespace is trimmed and tokens are split on runs of whitespace. A valid
     * command must use the exact uppercase verb and supply the required number of arguments. Extra arguments
     * are rejected.
     *
     * <p>Supported commands:
     * <ul>
     *   <li>{@code VERSION <version>} — protocol version handshake (1 argument)
     *   <li>{@code CONNECT <sessionId> <heartbeatTimeoutMs>} — session registration (2 arguments)
     *   <li>{@code HEARTBEAT} — heartbeat signal (0 arguments)
     *   <li>{@code TERMINATE} — session termination (0 arguments)
     * </ul>
     *
     * @param line the raw protocol line (may be null or blank)
     * @return the parsed command, or {@code null} if the line is invalid
     */
    public static Command parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        String verb = parts[0];

        switch (verb) {
            case CMD_VERSION:
                if (parts.length != 2) {
                    return null;
                }
                return new Command(verb, parts[1], null);

            case CMD_CONNECT:
                if (parts.length != 3) {
                    return null;
                }
                return new Command(verb, parts[1], parts[2]);

            case CMD_HEARTBEAT:
            case CMD_TERMINATE:
                if (parts.length != 1) {
                    return null;
                }
                return new Command(verb, null, null);

            default:
                return null;
        }
    }

    /**
     * A parsed protocol command.
     *
     * @param verb the command verb (VERSION, CONNECT, HEARTBEAT, TERMINATE)
     * @param arg1 the first argument (version string, session ID, or {@code null})
     * @param arg2 the second argument (heartbeat timeout milliseconds, or {@code null})
     */
    public record Command(String verb, String arg1, String arg2) {}
}
