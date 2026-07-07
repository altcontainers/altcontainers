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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.altcontainers.api.ContainerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton that launches the Reaper and manages the persistent TCP connection.
 */
public final class ReaperController {

    private static final Logger logger = LoggerFactory.getLogger(ReaperController.class);
    private static volatile ReaperController instance;

    private static final String FALLBACK_SUFFIX = " Labeled resources only cleaned if reaper runs separately.";
    private static final long DISCOVERY_POLL_INTERVAL_MS = 100L;

    private final Configuration configuration;
    private ResourceSession session;
    private volatile boolean connected;
    private volatile ReaperConnection reaperConnection;
    private final Object connectLock = new Object();
    private Thread shutdownHook;

    private ReaperController(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Returns the singleton instance, creating it lazily if needed.
     *
     * @return the singleton instance
     */
    public static ReaperController instance() {
        ReaperController local = instance;
        if (local != null) {
            return local;
        }
        synchronized (ReaperController.class) {
            local = instance;
            if (local == null) {
                Configuration config = Configuration.load();
                local = new ReaperController(config);
                instance = local;
            }
            return local;
        }
    }

    /**
     * Returns the session ID, or {@code null} if not connected.
     *
     * @return the session ID, or {@code null}
     */
    public String sessionId() {
        ensureReady();
        return session != null ? session.sessionId() : null;
    }

    /**
     * Returns the client configuration.
     *
     * @return the configuration
     */
    public Configuration configuration() {
        return configuration;
    }

    /**
     * Returns the reaper connection for shutdown hook use.
     *
     * @return the reaper connection, or {@code null}
     */
    ReaperConnection reaperConnection() {
        return reaperConnection;
    }

    /**
     * Ensures the reaper is launched and connected.
     *
     * @return the resource session
     * @throws ContainerException if the reaper fails to start or connect
     */
    public ResourceSession ensureReady() {
        synchronized (this) {
            if (session != null && connected) {
                return session;
            }
            if (session == null) {
                session = new ResourceSession();
            }
        }
        synchronized (connectLock) {
            synchronized (this) {
                if (connected) {
                    return session;
                }
            }
            if (configuration.disabled()) {
                logger.info("Reaper disabled." + FALLBACK_SUFFIX);
                return session;
            }
            try {
                if (!launchAndConnect()) {
                    throw new ContainerException("Reaper failed to start");
                }
                try {
                    registerShutdownHook();
                } catch (RuntimeException e) {
                    closeReaperConnection();
                    throw e;
                }
                synchronized (this) {
                    connected = true;
                }
            } catch (ContainerException e) {
                throw e;
            } catch (Exception e) {
                throw new ContainerException("Reaper unavailable: " + e.getMessage(), e);
            }
            return session;
        }
    }

    /**
     * Launches the reaper process and connects to it via TCP handshake.
     *
     * @return {@code true} on successful connection
     * @throws IOException if launch or connection fails
     */
    private boolean launchAndConnect() throws IOException {
        Launcher.launch(session.sessionId());

        long deadline = System.currentTimeMillis()
                + configuration.reaperStartupTimeout().toMillis();
        while (System.currentTimeMillis() < deadline) {
            var portOpt = ReaperDiscovery.readPort(session.sessionId());
            if (portOpt.isPresent()) {
                int port = portOpt.get();
                try {
                    reaperConnection = new ReaperConnection(connectAndHandshake(
                            "localhost",
                            port,
                            session.sessionId(),
                            configuration.reaperConnectionTimeout().toMillis()));
                    return true;
                } catch (ContainerException e) {
                    throw e;
                } catch (Exception e) {
                    /* keep polling */
                }
            }
            try {
                Thread.sleep(DISCOVERY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Opens a TCP socket to the reaper, performs the session ID handshake,
     * and returns the connected socket.
     *
     * @param host the reaper host
     * @param port the reaper port
     * @param sessionId the session ID
     * @param timeoutMilliseconds the connection and handshake timeout
     * @return the connected socket
     * @throws IOException if connection or handshake fails
     */
    static Socket connectAndHandshake(String host, int port, String sessionId, long timeoutMilliseconds)
            throws IOException {
        Socket socket = new Socket();
        boolean connected = false;
        try {
            int timeout = (int) Math.min(timeoutMilliseconds, Integer.MAX_VALUE);
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);

            var writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.write(sessionId + "\n");
            writer.flush();

            String response = reader.readLine();
            if (!"OK".equals(response)) {
                throw new ContainerException("Reaper handshake failed: expected 'OK', got '" + response + "'");
            }
            socket.setSoTimeout(0);
            connected = true;
            return socket;
        } finally {
            if (!connected) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Best-effort close after failed handshake.
                }
            }
        }
    }

    /**
     * Registers a JVM shutdown hook that sends TERMINATE to the reaper
     * and closes the connection.
     */
    private void registerShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                return;
            }
        }
        shutdownHook = new Thread(
                () -> {
                    ReaperConnection conn = reaperConnection;
                    if (conn != null) {
                        try {
                            conn.sendTerminate();
                        } catch (IOException ignored) {
                        }
                        conn.close();
                    }
                },
                "altcontainers-reaper-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            /* shutdown in progress */
        }
    }

    /**
     * Closes the reaper connection and clears the reference.
     */
    private void closeReaperConnection() {
        ReaperConnection conn = reaperConnection;
        if (conn != null) {
            conn.close();
            reaperConnection = null;
        }
    }
}
