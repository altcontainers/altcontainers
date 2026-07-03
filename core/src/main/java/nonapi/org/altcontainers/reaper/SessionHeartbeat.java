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
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background daemon thread that sends periodic {@code HEARTBEAT} commands to the global reaper.
 *
 * <p>Runs until {@link #stop()} is called or the socket write fails. On write failure, the thread
 * stops silently (the reaper's heartbeat scanner will eventually clean up the session).
 */
final class SessionHeartbeat implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SessionHeartbeat.class);

    private final PrintWriter writer;

    private final BufferedReader reader;

    private final long intervalMs;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Runnable onDisconnect;

    /**
     * Creates a new heartbeat sender.
     *
     * @param writer the socket writer; must not be {@code null}
     * @param reader the socket reader for checking server responses; may be {@code null}
     * @param intervalMs heartbeat interval in milliseconds; must be positive
     * @param onDisconnect callback invoked when the heartbeat fails (connection lost)
     */
    SessionHeartbeat(PrintWriter writer, BufferedReader reader, long intervalMs, Runnable onDisconnect) {
        this.writer = writer;
        this.reader = reader;
        this.intervalMs = intervalMs;
        this.onDisconnect = onDisconnect;
    }

    /**
     * Signals the heartbeat thread to stop at the next sleep cycle.
     */
    void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running.get()) {
                break;
            }
            try {
                // Snapshot volatile writer field. A concurrent closeSocket() from the
                // shutdown hook may null the field, but the local reference still points
                // to the (possibly already-closed) PrintWriter. Writing to a closed socket
                // may pass checkError() silently for one heartbeat cycle; the reaper's
                // heartbeat scanner independently handles session timeout. This race is benign.
                PrintWriter w = writer;
                BufferedReader r = reader;
                if (w != null) {
                    synchronized (w) {
                        w.println(Protocol.CMD_HEARTBEAT);
                        w.flush();
                        if (w.checkError()) {
                            logger.warn("Heartbeat write failed, session will be cleaned up by reaper");
                            running.set(false);
                            onDisconnect.run();
                            break;
                        }
                    }
                    // Read and validate the server's response line when a reader
                    // is available. A non-OK response (e.g. "ERROR unknown session")
                    // means the reaper has orphaned this session — stop the heartbeat
                    // and let the client re-establish on the next ensureReady().
                    if (r != null) {
                        String response;
                        try {
                            response = r.readLine();
                        } catch (IOException e) {
                            logger.warn("Heartbeat response read failed: {}", e.getMessage());
                            running.set(false);
                            onDisconnect.run();
                            break;
                        }
                        if (response == null || !response.startsWith(Protocol.RSP_OK)) {
                            logger.warn("Heartbeat rejected by reaper: {}", response);
                            running.set(false);
                            onDisconnect.run();
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("Heartbeat failed, session will be cleaned up by reaper: {}", e.getMessage());
                running.set(false);
                onDisconnect.run();
                break;
            }
        }
    }
}
