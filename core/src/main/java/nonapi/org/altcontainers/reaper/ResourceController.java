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
import java.nio.file.Files;
import nonapi.org.altcontainers.DockerClient;
import org.altcontainers.api.ContainerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parent-side singleton that discovers (or auto-launches) the global reaper daemon and manages
 * the heartbeat TCP connection.
 *
 * <p>Cleanup is label-authoritative: resources are labeled at creation time and the reaper
 * identifies them by label. No explicit register/unregister calls are needed.
 */
public final class ResourceController {

    private static final Logger logger = LoggerFactory.getLogger(ResourceController.class);

    private static volatile ResourceController instance;

    private static final String FALLBACK_SUFFIX =
            " Labeled resources will only be cleaned if a daemon is running separately.";

    private static final long DISCOVERY_POLL_INTERVAL_MS = 500L;

    private static final long DISCOVERY_POLL_TIMEOUT_MS = 10_000L;

    private final Configuration configuration;

    private final DockerClient dockerClient;

    private ResourceSession session;

    private volatile Socket socket;

    private volatile BufferedReader reader;

    private volatile PrintWriter writer;

    private volatile boolean connected;

    private volatile Thread heartbeatThread;

    private volatile SessionHeartbeat heartbeat;

    /**
     * Lock for the discovery/launch phase, separate from the instance monitor.
     * Prevents concurrent discovery attempts while keeping the fast-path check
     * O(1) under the instance monitor.
     */
    private final Object connectLock = new Object();

    /**
     * The registered shutdown hook Thread, or {@code null} if none is registered.
     * Managed exclusively by {@link #registerShutdownHook()} under the instance monitor.
     */
    private Thread shutdownHook;

    private ResourceController(Configuration configuration, DockerClient dockerClient) {
        this.configuration = configuration;
        this.dockerClient = dockerClient;
    }

    /**
     * Returns the singleton {@link ResourceController} instance.
     *
     * @return the shared instance
     */
    public static ResourceController instance() {
        ResourceController local = instance;
        if (local != null) {
            return local;
        }
        synchronized (ResourceController.class) {
            local = instance;
            if (local == null) {
                Configuration reaperConfig = Configuration.load();
                DockerClient client = DockerClient.instance();
                local = new ResourceController(reaperConfig, client);
                instance = local;
            }
            return local;
        }
    }

    /**
     * Ensures the reaper daemon is connected and returns the current session.
     *
     * <p>The instance monitor guards only the fast-path read/write of
     * {@code session} and {@code connected}. The discovery/launch loop
     * (which may sleep up to {@link #DISCOVERY_POLL_TIMEOUT_MS}) runs
     * under {@code connectLock} so that concurrent callers are not
     * serialized behind the discovery sleep.
     *
     * @return the current resource session
     */
    public ResourceSession ensureReady() {
        // Fast path: already connected. O(1) under instance monitor.
        synchronized (this) {
            if (session != null && connected) {
                return session;
            }
            if (session == null) {
                session = new ResourceSession();
            }
        }

        // Slow path: discover or launch. Serialized under connectLock so
        // only one thread performs discovery.
        synchronized (connectLock) {
            // Re-check: another thread may have connected while we waited for connectLock.
            synchronized (this) {
                if (connected) {
                    return session;
                }
            }

            if (configuration.disabled()) {
                logger.info("Reaper daemon disabled." + FALLBACK_SUFFIX);
                return session;
            }

            try {
                if (!discoverOrLaunch()) {
                    logger.info("Reaper daemon unavailable." + FALLBACK_SUFFIX);
                    return session;
                }
                handshake();
                startHeartbeat();
                registerShutdownHook();
                synchronized (this) {
                    connected = true;
                }
            } catch (ContainerException | IOException e) {
                logger.info("Reaper daemon unavailable: " + e.getMessage() + "." + FALLBACK_SUFFIX);
                closeSocket();
            }

            return session;
        }
    }

    private boolean discoverOrLaunch() throws IOException {
        var entry = ReaperDiscovery.read();
        if (entry.isPresent() && ReaperDiscovery.isAlive(entry.get())) {
            try {
                socket = new Socket("localhost", entry.get().port());
                return true;
            } catch (IOException e) {
                // PID was alive but connection failed — maybe just died. Fall through to auto-launch.
                logger.debug("Connection to discovered reaper failed, attempting auto-launch", e);
            }
        }

        // Stale file or absent.
        if (entry.isPresent()) {
            try {
                Files.deleteIfExists(ReaperDiscovery.discoveryPath());
            } catch (IOException ignored) {
                // Best-effort.
            }
        }

        Launcher.launch(configuration);

        long deadline = System.currentTimeMillis() + DISCOVERY_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(DISCOVERY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            var polled = ReaperDiscovery.read();
            if (polled.isPresent() && ReaperDiscovery.isAlive(polled.get())) {
                try {
                    socket = new Socket("localhost", polled.get().port());
                    return true;
                } catch (IOException e) {
                    // Keep polling.
                }
            }
        }
        return false;
    }

    private void handshake() throws IOException {
        socket.setSoTimeout(toSoTimeout(configuration.connectionTimeoutMilliseconds()));
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);

        writer.println(Protocol.CMD_VERSION + " " + Protocol.VERSION);
        String response = reader.readLine();
        if (response == null || !response.startsWith(Protocol.RSP_OK)) {
            closeSocket();
            throw new ContainerException("VERSION handshake failed: " + response);
        }

        writer.println(
                Protocol.CMD_CONNECT + " " + session.sessionId() + " " + configuration.sessionTimeoutMilliseconds());
        response = reader.readLine();
        if (response == null || !Protocol.RSP_OK.equals(response)) {
            closeSocket();
            throw new ContainerException("CONNECT failed: " + response);
        }
    }

    private void startHeartbeat() {
        heartbeat = new SessionHeartbeat(
                writer, reader, configuration.heartbeatIntervalMilliseconds(), () -> connected = false);
        heartbeatThread = new Thread(heartbeat, "altcontainers-reaper-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Shutdown in progress; the existing hook will run. Skip adding a new one.
                return;
            }
        }
        shutdownHook = new Thread(
                () -> {
                    if (heartbeatThread != null) {
                        heartbeat.stop();
                        heartbeatThread.interrupt();
                    }
                    if (writer != null) {
                        synchronized (writer) {
                            try {
                                writer.println(Protocol.CMD_TERMINATE);
                                writer.flush();
                            } catch (RuntimeException ignored) {
                                // Best-effort.
                            }
                        }
                    }
                    if (reader != null) {
                        try {
                            reader.readLine();
                        } catch (IOException ignored) {
                            // Best-effort.
                        }
                    }
                    closeSocket();
                },
                "altcontainers-reaper-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // Shutdown in progress; the reaper daemon's own lifecycle
            // (heartbeat scanner, idle timer) handles cleanup.
            logger.info("Shutdown in progress, skipping shutdown-hook registration");
            return;
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        reader = null;
        writer = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Best-effort.
            }
        }
    }

    private static int toSoTimeout(long millis) {
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }
}
