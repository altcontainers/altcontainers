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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import nonapi.org.altcontainers.DockerClient;
import org.altcontainers.api.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-connection handler for the global reaper daemon.
 *
 * <p>Performs the {@code VERSION}/{@code CONNECT} handshake, processes {@code HEARTBEAT} and
 * {@code TERMINATE} commands, and on disconnect lets the heartbeat scanner handle cleanup.
 */
public final class Handler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Handler.class);

    private final Socket socket;

    private final BufferedReader reader;

    private final PrintWriter writer;

    private final DockerClient dockerClient;

    private final Configuration configuration;

    private final ConcurrentHashMap<String, SessionState> sessions;

    private final AtomicInteger sessionCount;

    private final AtomicInteger pendingConnections;

    private String sessionId;

    /**
     * Creates a handler wrapping the given socket.
     *
     * @param socket the accepted socket
     * @param dockerClient the Docker client
     * @param configuration the reaper configuration
     * @param sessions the shared session map
     * @param sessionCount the shared connected-session counter for idle timeout
     * @param pendingConnections the shared accepted-but-not-yet-handshaked counter for idle timeout;
     *     decremented exactly once when this handler terminates
     * @throws IOException if the socket streams cannot be obtained
     */
    public Handler(
            Socket socket,
            DockerClient dockerClient,
            Configuration configuration,
            ConcurrentHashMap<String, SessionState> sessions,
            AtomicInteger sessionCount,
            AtomicInteger pendingConnections)
            throws IOException {
        this.socket = socket;
        this.dockerClient = dockerClient;
        this.configuration = configuration;
        this.sessions = sessions;
        this.sessionCount = sessionCount;
        this.pendingConnections = pendingConnections;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.writer = new PrintWriter(socket.getOutputStream(), true);
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(toSoTimeoutMs(configuration.connectionTimeoutMilliseconds()));
            if (!doHandshake()) {
                return;
            }
            socket.setSoTimeout(0);
            serveLoop();
        } catch (IOException e) {
            // Connection closed or I/O error; scanner handles cleanup.
        } finally {
            onDisconnect();
            // A connection was counted as pending on accept; release it now regardless of whether
            // the handshake completed. (On success sessionCount was already incremented in
            // doHandshake; the transient overlap is harmless because the idle timer only acts at 0.)
            pendingConnections.decrementAndGet();
        }
    }

    /**
     * Performs the {@code VERSION} and {@code CONNECT} exchange.
     *
     * @return {@code true} if the handshake completed successfully
     * @throws IOException on socket error
     */
    private boolean doHandshake() throws IOException {
        String line = reader.readLine();
        Protocol.Command cmd = Protocol.parse(line);
        if (cmd == null || !Protocol.CMD_VERSION.equals(cmd.verb())) {
            sendError("expected VERSION");
            return false;
        }
        String clientVersion = cmd.arg1();
        if (!Protocol.VERSION.equals(clientVersion)) {
            sendError("unsupported version");
            return false;
        }
        sendOk("VERSION " + Protocol.VERSION + " " + Version.version());

        line = reader.readLine();
        cmd = Protocol.parse(line);
        if (cmd == null || !Protocol.CMD_CONNECT.equals(cmd.verb())) {
            sendError("expected CONNECT");
            return false;
        }
        if (cmd.arg1() == null || cmd.arg2() == null) {
            sendError("CONNECT requires <sessionId> <heartbeatTimeoutMs>");
            return false;
        }
        String sid = cmd.arg1();
        try {
            UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            sendError("invalid session id");
            return false;
        }

        long heartbeatTimeoutMs;
        try {
            heartbeatTimeoutMs = Long.parseLong(cmd.arg2());
        } catch (NumberFormatException e) {
            sendError("heartbeat timeout must be a valid long");
            return false;
        }
        if (heartbeatTimeoutMs <= 0) {
            sendError("heartbeat timeout must be positive");
            return false;
        }
        if (heartbeatTimeoutMs < 1_000) {
            sendError("heartbeat timeout must be at least 1000");
            return false;
        }

        SessionState candidate = new SessionState(sid, heartbeatTimeoutMs, new AtomicLong(System.nanoTime()), socket);
        if (sessions.putIfAbsent(sid, candidate) != null) {
            sendError("session already connected");
            return false;
        }

        sessionId = sid;
        sessionCount.incrementAndGet();
        sendOk();
        logger.info("Session connected " + sessionId);
        return true;
    }

    /**
     * Processes {@code HEARTBEAT} and {@code TERMINATE} commands until the client disconnects.
     *
     * @throws IOException on socket error
     */
    private void serveLoop() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            Protocol.Command cmd = Protocol.parse(line);
            if (cmd == null) {
                sendError("invalid command");
                continue;
            }
            switch (cmd.verb()) {
                case Protocol.CMD_HEARTBEAT -> {
                    SessionState s = sessions.get(sessionId);
                    if (s == null) {
                        sendError("unknown session");
                        continue;
                    }
                    s.lastHeartbeatNanos().set(System.nanoTime());
                    sendOk();
                }
                case Protocol.CMD_TERMINATE -> {
                    SessionState removed = sessions.remove(sessionId);
                    if (removed != null) {
                        sessionCount.decrementAndGet();
                    }
                    logger.info("Cleaning up session " + sessionId);
                    try {
                        ResourceCleaner.cleanupSession(
                                dockerClient, sessionId, configuration.cleanupTimeoutMilliseconds());
                        logger.info("Session " + sessionId + " cleaned up");
                    } catch (RuntimeException e) {
                        logger.error("Cleanup failed for session " + sessionId + ": " + e.getMessage());
                    }
                    sendOk();
                    closeSocket();
                    return;
                }
                default -> sendError("invalid command");
            }
        }
    }

    /**
     * Called on disconnect (EOF, I/O error, or finally block).
     *
     * <p>Does NOT clean up the session — the heartbeat scanner handles cleanup after timeout.
     * Just nulls out the connection reference in the session state so the scanner knows the
     * connection is gone.
     */
    private void onDisconnect() {
        if (sessionId != null) {
            SessionState s = sessions.get(sessionId);
            if (s != null) {
                // Null out the connection reference; scanner will handle cleanup.
                sessions.replace(
                        sessionId,
                        s,
                        new SessionState(s.sessionId(), s.heartbeatTimeoutMs(), s.lastHeartbeatNanos(), null));
            }
        }
        closeSocket();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    private static int toSoTimeoutMs(long millis) {
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private void sendOk() {
        writer.println(Protocol.RSP_OK);
    }

    private void sendOk(String message) {
        writer.println(Protocol.RSP_OK + " " + message);
    }

    private void sendError(String message) {
        writer.println(Protocol.RSP_ERROR + " " + message);
    }
}
