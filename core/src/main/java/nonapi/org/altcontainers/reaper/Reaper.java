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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import nonapi.org.altcontainers.DockerClient;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry point for the global reaper daemon.
 *
 * <p>Starts as a multi-session TCP server. Requires {@code -Daltcontainers.reaper.daemon=true}.
 * Binds to an OS-assigned port, writes a discovery file, and accepts connections from
 * multiple test JVMs. A heartbeat scanner cleans up timed-out sessions. An idle timer
 * self-terminates the daemon when no sessions are connected for the configured idle duration.
 */
public final class Reaper {

    private static final Logger logger = LoggerFactory.getLogger(Reaper.class);

    private static final long SCANNER_INTERVAL_MS = 1_000L;

    private static final long IDLE_CHECK_INTERVAL_MS = 1_000L;

    private static final String MAX_LOG_FILE_SIZE = "1MB";
    private static final int MAX_LOG_FILE_INDEX = 9;
    private static final int MIN_LOG_FILE_INDEX = 1;

    private Reaper() {
        // Intentionally empty
    }

    /**
     * Entry point. Requires {@code -Daltcontainers.reaper.daemon=true}.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        if (!"true".equals(System.getProperty("altcontainers.reaper.daemon"))) {
            System.err.println("Usage: java -Daltcontainers.reaper.daemon=true ... " + Reaper.class.getName());
            System.exit(1);
        }

        String logDirectory = System.getProperty("altcontainers.reaper.log.directory");
        if (logDirectory != null) {
            String logFile = logDirectory + File.separator + "reaper-"
                    + ProcessHandle.current().pid() + ".log";
            configureLogback(logFile);
            redirectStdout(logFile);
        }
        logHeader();

        try {
            runServer();
            exit(0);
        } catch (Throwable t) {
            logger.error("Fatal error: " + t.getMessage(), t);
            exit(1);
        }
    }

    private static void runServer() {
        Configuration configuration = Configuration.load();

        DockerClient client;
        try {
            client = DockerClient.instance();
        } catch (ContainerException e) {
            logger.error("Failed to initialize Docker client: " + e.getMessage());
            exit(1);
            return;
        }

        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(0);
        } catch (IOException e) {
            logger.error("Failed to bind server socket: " + e.getMessage());
            exit(1);
            return;
        }

        int port = serverSocket.getLocalPort();
        try {
            ReaperDiscovery.writePort(port);
        } catch (IOException e) {
            logger.error("Failed to write discovery file: " + e.getMessage());
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Best-effort.
            }
            exit(1);
            return;
        }
        logger.info("Reaper listening on port " + port);

        ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
        AtomicInteger sessionCount = new AtomicInteger(0);
        AtomicInteger pendingConnections = new AtomicInteger(0);

        Thread scannerThread = startScanner(sessions, sessionCount, client, configuration);
        Thread idleThread =
                startIdleTimer(sessionCount, pendingConnections, configuration, serverSocket, sessions, client);

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            logger.info("Reaper shutting down");
                            ReaperDiscovery.delete();
                            try {
                                serverSocket.close();
                            } catch (IOException ignored) {
                                // Best-effort.
                            }
                            for (SessionState s : sessions.values()) {
                                try {
                                    ResourceCleaner.cleanupSession(
                                            client, s.sessionId(), configuration.cleanupTimeoutMilliseconds());
                                } catch (RuntimeException e) {
                                    logger.error("Cleanup failed for session " + s.sessionId() + ": " + e.getMessage());
                                }
                            }
                        },
                        "altcontainers-reaper-shutdown"));

        try {
            while (true) {
                Socket socket = serverSocket.accept();
                pendingConnections.incrementAndGet();
                try {
                    Handler handler =
                            new Handler(socket, client, configuration, sessions, sessionCount, pendingConnections);
                    Thread handlerThread = new Thread(handler, "altcontainers-reaper-handler");
                    handlerThread.setDaemon(true);
                    handlerThread.start();
                } catch (IOException e) {
                    // Handler construction failed (e.g. socket streams unreadable); release the
                    // pending count so the idle timer does not see a phantom in-flight connection.
                    pendingConnections.decrementAndGet();
                    logger.error("Failed to create handler: " + e.getMessage());
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Best-effort.
                    }
                }
            }
        } catch (SocketException e) {
            // ServerSocket closed — expected during shutdown.
        } catch (IOException e) {
            logger.error("Accept loop failed: " + e.getMessage());
        }
    }

    private static Thread startScanner(
            ConcurrentHashMap<String, SessionState> sessions,
            AtomicInteger sessionCount,
            DockerClient client,
            Configuration configuration) {
        Thread scanner = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(SCANNER_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        long now = System.nanoTime();
                        for (SessionState s : sessions.values()) {
                            long elapsedNs = now - s.lastHeartbeatNanos().get();
                            long timeoutNs = s.heartbeatTimeoutMs() * 1_000_000L;
                            if (elapsedNs > timeoutNs) {
                                // Re-read lastHeartbeatNanos to close the race window.
                                // A heartbeat arriving between the snapshot read above
                                // and the remove below would reset the timestamp; the
                                // fresh check prevents premature eviction.
                                SessionState current = sessions.get(s.sessionId());
                                if (current == null) {
                                    continue;
                                }
                                long freshElapsedNs =
                                        now - current.lastHeartbeatNanos().get();
                                if (freshElapsedNs <= current.heartbeatTimeoutMs() * 1_000_000L) {
                                    continue;
                                }
                                SessionState removed = sessions.remove(s.sessionId());
                                if (removed != null) {
                                    sessionCount.decrementAndGet();
                                    logger.info("Session " + s.sessionId() + " timed out after "
                                            + (elapsedNs / 1_000_000L)
                                            + "ms");
                                    try {
                                        ResourceCleaner.cleanupSession(
                                                client, s.sessionId(), configuration.cleanupTimeoutMilliseconds());
                                        logger.info("Session " + s.sessionId() + " cleaned up");
                                    } catch (RuntimeException e) {
                                        logger.error(
                                                "Cleanup failed for session " + s.sessionId() + ": " + e.getMessage());
                                    }
                                    closeSessionSocket(s);
                                }
                            }
                        }
                    }
                },
                "altcontainers-reaper-scanner");
        scanner.setDaemon(true);
        scanner.start();
        return scanner;
    }

    /**
     * Returns whether the reaper daemon should be considered active (non-idle).
     *
     * <p>The daemon is active when there is at least one fully-connected session OR at least one
     * accepted connection still completing its handshake. The idle timer reads this conservatively
     * from two {@link AtomicInteger}s: it only ever shuts down when both read as zero, so a connection
     * that arrives after a read is caught on the next idle interval. This keeps the idle timer from
     * shutting the daemon down while a client is mid-handshake (during which {@code sessionCount} is
     * still zero but {@code pendingConnections} is non-zero).
     *
     * @param sessionCount the connected-session count
     * @param pendingConnections the accepted-but-not-yet-handshaked count
     * @return {@code true} if either count is greater than zero
     */
    static boolean isDaemonActive(int sessionCount, int pendingConnections) {
        return sessionCount > 0 || pendingConnections > 0;
    }

    private static Thread startIdleTimer(
            AtomicInteger sessionCount,
            AtomicInteger pendingConnections,
            Configuration configuration,
            ServerSocket serverSocket,
            ConcurrentHashMap<String, SessionState> sessions,
            DockerClient client) {
        if (configuration.idleTimeoutMilliseconds() == 0) {
            return null;
        }
        Thread idle = new Thread(
                () -> {
                    long idleDeadlineNanos = 0;
                    boolean counting = false;
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(IDLE_CHECK_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (isDaemonActive(sessionCount.get(), pendingConnections.get())) {
                            counting = false;
                            continue;
                        }
                        if (!counting) {
                            idleDeadlineNanos =
                                    System.nanoTime() + configuration.idleTimeoutMilliseconds() * 1_000_000L;
                            counting = true;
                            continue;
                        }
                        if (System.nanoTime() >= idleDeadlineNanos) {
                            logger.info("Idle timeout reached, shutting down");
                            ReaperDiscovery.delete();
                            try {
                                serverSocket.close();
                            } catch (IOException ignored) {
                                // Best-effort.
                            }
                            for (SessionState s : sessions.values()) {
                                try {
                                    ResourceCleaner.cleanupSession(
                                            client, s.sessionId(), configuration.cleanupTimeoutMilliseconds());
                                } catch (RuntimeException e) {
                                    logger.error("Cleanup failed for session " + s.sessionId() + ": " + e.getMessage());
                                }
                            }
                            break;
                        }
                    }
                },
                "altcontainers-reaper-idle");
        idle.setDaemon(true);
        idle.start();
        return idle;
    }

    private static void closeSessionSocket(SessionState s) {
        Socket conn = s.connection();
        if (conn != null) {
            try {
                conn.close();
            } catch (IOException ignored) {
                // Best-effort.
            }
        }
    }

    /**
     * Configures logback with a file-only appender for the reaper daemon process.
     *
     * <p>Resets any inherited logback configuration from the parent JVM's classpath and sets
     * up a fresh configuration: a single {@link RollingFileAppender} writing to {@code logPath} with a
     * pattern layout, and a root logger at the level from {@link Configuration#logLevel()}. No
     * console appender.
     *
     * <p>Best-effort: if the log file cannot be opened, the daemon still functions, just
     * without a log.
     *
     * @param logPath the log file path
     */
    static void configureLogback(String logPath) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} | %level | %logger | %msg%n");
        encoder.setContext(context);
        encoder.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setFile(logPath);
        appender.setEncoder(encoder);
        appender.setContext(context);

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy = new SizeBasedTriggeringPolicy<>();
        triggeringPolicy.setMaxFileSize(FileSize.valueOf(MAX_LOG_FILE_SIZE));
        triggeringPolicy.setContext(context);

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setFileNamePattern(logPath + ".%i");
        rollingPolicy.setMinIndex(MIN_LOG_FILE_INDEX);
        rollingPolicy.setMaxIndex(MAX_LOG_FILE_INDEX);
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(appender);

        try {
            triggeringPolicy.start();
            rollingPolicy.start();
            appender.setTriggeringPolicy(triggeringPolicy);
            appender.setRollingPolicy(rollingPolicy);
            appender.start();
        } catch (RuntimeException e) {
            // Best-effort; daemon still functions, just no log.
            return;
        }

        Configuration configuration = Configuration.load();
        Level level = Level.toLevel(configuration.logLevel(), Level.INFO);

        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(level);
        rootLogger.addAppender(appender);
    }

    /**
     * Redirects {@code System.out} to the given log file.
     *
     * <p>Defensive capture for any stray {@code System.out.println} calls. {@code System.err}
     * is intentionally not redirected — it is discarded by the Launcher's
     * {@code ProcessBuilder.Redirect.DISCARD}.
     *
     * @param logPath the log file path
     */
    private static void redirectStdout(String logPath) {
        try {
            FileOutputStream fos = new FileOutputStream(logPath, true);
            PrintStream ps = new PrintStream(fos, true, StandardCharsets.UTF_8);
            System.setOut(ps);
        } catch (IOException e) {
            // Best-effort; daemon still functions, just no log.
        }
    }

    static void logHeader() {
        String version = Version.version();
        logger.info("Altcontainers Reaper v" + version);
    }

    static String exitMessage(int code) {
        return "Altcontainers Reaper v" + Version.version() + " exit-code=[" + code + "]";
    }

    private static void exit(int code) {
        logger.info(exitMessage(code));
        System.exit(code);
    }
}
