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

package org.altcontainers.reaper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaper — standalone watchdog process for Docker resource cleanup.
 *
 * <p>Launched by the core module. Watches a single persistent TCP
 * connection for liveness. When the connection drops, cleans up all
 * Docker resources labeled with the session ID.
 *
 * <p>Cleanup is verified, not fire-and-forget: the session is swept repeatedly (bounded by
 * {@link #MAX_SWEEPS} and the drain deadline) until the daemon confirms that no labeled resource
 * remains. Resources that survive every sweep, or a listing failure, are logged and reported through
 * a non-zero exit status instead of being silently abandoned.
 */
public final class Reaper {

    private static final Logger logger = LoggerFactory.getLogger(Reaper.class);
    private static final long GRACE_PERIOD_MS = 30_000L;

    /** Part of the session-sweep drain deadline reserved for removing networks. */
    static final long NETWORK_PHASE_RESERVE_MS = 60_000L;

    /** Maximum number of full session sweeps; each sweep re-discovers and retries leftovers. */
    static final int MAX_SWEEPS = 4;

    /** Pause between sweeps so the daemon can settle in-flight removals. */
    static final long SWEEP_PAUSE_MS = 1_000L;

    /** Attempts for a session resource listing before the sweep reports incomplete discovery. */
    static final int LIST_ATTEMPTS = 3;

    /** Base delay between session resource listing attempts. */
    static final long LIST_RETRY_DELAY_MS = 250L;

    /** Exit status reported when labeled resources could not be removed. */
    static final int EXIT_LEFTOVERS = 2;

    private static final String LOG_EXTENSION = ".log";
    private static final String PORT_EXTENSION = ".port";
    private static final String JAR_EXTENSION = ".jar";
    private static final long ACCEPT_TIMEOUT_MS = 60_000L;
    static final int STOP_TIMEOUT_MS = parseStopTimeoutMsFromProperty();
    private static final int HANDSHAKE_TIMEOUT_MILLISECONDS =
            parsePositiveMillisecondsProperty("altcontainers.reaper.connection.timeout.ms", "10000");

    static final int MAX_ATTEMPTS;

    static {
        String raw = System.getProperty("altcontainers.reaper.cleanup.max.attempts", "5");
        try {
            MAX_ATTEMPTS = parseMaxAttempts(raw);
        } catch (IllegalArgumentException e) {
            exitWithError(e);
            throw e; // unreachable, satisfies definite assignment
        }
    }

    /**
     * Reports an invalid property value on stderr and terminates the process.
     * Returns only if {@link System#exit} is blocked, in which case the caller
     * keeps its own unreachable fallback.
     *
     * @param failure the validation failure
     */
    private static void exitWithError(IllegalArgumentException failure) {
        System.err.println("Reaper: " + failure.getMessage());
        System.exit(1);
    }

    /**
     * Parses the stop timeout from the system property, exiting the process
     * on invalid input.
     *
     * @return the stop timeout in milliseconds
     */
    private static int parseStopTimeoutMsFromProperty() {
        String raw = System.getProperty("altcontainers.reaper.stop.timeout.ms", "30000");
        try {
            return parseStopTimeoutMs(raw);
        } catch (IllegalArgumentException e) {
            exitWithError(e);
            return 5000; // unreachable
        }
    }

    /**
     * Parses the stop timeout from a raw string value.
     *
     * @param raw the raw system property value
     * @return the stop timeout in milliseconds
     * @throws IllegalArgumentException if the value is not a positive integer
     */
    static int parseStopTimeoutMs(String raw) {
        int value = parseIntegerProperty("altcontainers.reaper.stop.timeout.ms", raw);
        if (value <= 0) {
            throw new IllegalArgumentException("altcontainers.reaper.stop.timeout.ms must be positive");
        }
        return value;
    }

    /**
     * Parses the maximum cleanup attempts from a raw string value.
     *
     * @param raw the raw system property value
     * @return the parsed maximum attempts
     * @throws IllegalArgumentException if the value is not an integer &gt;= 1
     */
    static int parseMaxAttempts(String raw) {
        int value = parseIntegerProperty("altcontainers.reaper.cleanup.max.attempts", raw);
        if (value < 1) {
            throw new IllegalArgumentException("altcontainers.reaper.cleanup.max.attempts must be >= 1");
        }
        return value;
    }

    /**
     * Parses a positive millisecond property from the system properties.
     *
     * @param key the system property key
     * @param defaultValue the default value if the property is not set
     * @return the parsed value in milliseconds
     * @throws IllegalArgumentException if the value is not a positive integer
     */
    private static int parsePositiveMillisecondsProperty(String key, String defaultValue) {
        String raw = System.getProperty(key, defaultValue);
        int value = parseIntegerProperty(key, raw);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    /**
     * Parses an integer from a raw property value.
     *
     * @param key the property key (for error messages)
     * @param raw the raw property value
     * @return the parsed integer
     * @throws IllegalArgumentException if the value is not a valid integer
     */
    private static int parseIntegerProperty(String key, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid integer, got '" + raw + "'", e);
        }
    }

    /**
     * Resolves the reaper log file path.
     *
     * <p>Uses the {@code altcontainers.reaper.log.directory} system property when set;
     * otherwise defaults to {@code java.io.tmpdir}, making the log file a sibling of
     * the extracted reaper JAR.
     *
     * @param sessionId the session UUID
     * @return the log file path, never {@code null}
     */
    static String resolveLogPath(String sessionId) {
        String logDirectory = System.getProperty("altcontainers.reaper.log.directory");
        if (logDirectory == null || logDirectory.isBlank()) {
            logDirectory = System.getProperty("java.io.tmpdir");
        }
        return logDirectory + File.separator + sessionFileName(sessionId, LOG_EXTENSION);
    }

    private Reaper() {
        // Intentionally empty
    }

    /**
     * Entry point.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        String sessionId = System.getProperty("altcontainers.reaper.session.id");
        if (sessionId == null || sessionId.isBlank()) {
            System.err.println("Missing -Daltcontainers.reaper.session.id");
            System.exit(1);
        }

        String logFile = resolveLogPath(sessionId);
        configureLogback(logFile);
        logHeader(sessionId);

        DockerClient dockerClient = createDockerClient();
        try {
            System.exit(exitCode(run(sessionId, dockerClient)));
        } catch (Throwable t) {
            logger.error("Fatal error: {}", t.getMessage(), t);
            System.exit(1);
        } finally {
            try {
                dockerClient.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Creates a cleanup executor with production defaults for timeout values.
     *
     * @param client the Docker client
     * @param maxAttempts the maximum number of attempts per resource per sweep
     * @return a new cleanup executor
     */
    static CleanupExecutor createCleanupExecutor(DockerClient client, int maxAttempts) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CleanupExecutor.POOL_SIZE, r -> {
            Thread t = new Thread(r, "altcontainers-reaper-cleanup-scheduler");
            t.setDaemon(true);
            return t;
        });
        return new CleanupExecutor(
                client, maxAttempts, STOP_TIMEOUT_MS + 10_000L, 10_000L, scheduler, Clock.systemUTC());
    }

    /**
     * Runs the reaper lifecycle: binds a server socket, writes the port file,
     * accepts a connection, performs the session handshake, watches for
     * liveness, and cleans up resources on disconnect or termination.
     *
     * @param sessionId the session UUID
     * @param dockerClient the Docker client
     */
    private static CleanupResult run(String sessionId, DockerClient dockerClient) {

        ServerSocket serverSocket;
        int port;
        try {
            serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"));
            serverSocket.setSoTimeout((int) ACCEPT_TIMEOUT_MS);
            port = serverSocket.getLocalPort();
        } catch (IOException e) {
            logger.error("Failed to bind server socket: {}", e.getMessage());
            System.exit(1);
            return CleanupResult.incomplete();
        }

        try {
            writePortFile(port, sessionId);
        } catch (IOException e) {
            logger.error("Failed to write port file: {}", e.getMessage());
            closeQuietly(serverSocket);
            System.exit(1);
            return CleanupResult.incomplete();
        }
        logger.info("Port {}", port);

        Socket clientSocket;
        try {
            clientSocket = serverSocket.accept();
            serverSocket.close();
        } catch (SocketTimeoutException e) {
            logger.error("No connection received within {}ms timeout", ACCEPT_TIMEOUT_MS);
            failAccept(sessionId, port, serverSocket);
            return CleanupResult.incomplete();
        } catch (IOException e) {
            logger.error("Accept failed: {}", e.getMessage());
            failAccept(sessionId, port, serverSocket);
            return CleanupResult.incomplete();
        }

        CleanupExecutor executor = createCleanupExecutor(dockerClient, MAX_ATTEMPTS);

        boolean receivedTerminate;
        try {
            receivedTerminate =
                    receiveSessionAndWatch(clientSocket, sessionId, HANDSHAKE_TIMEOUT_MILLISECONDS, executor);
            logger.info("Disconnected (terminate={})", receivedTerminate);
        } catch (HandshakeException e) {
            logger.warn(e.getMessage());
            cleanupOnHandshakeFailure(sessionId, port, clientSocket, executor);
            System.exit(1);
            return CleanupResult.incomplete();
        } catch (IOException e) {
            logger.info("Connection error for session {}: {}", sessionId, e.getMessage());
            receivedTerminate = false;
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }

        long graceMs = receivedTerminate ? 0L : GRACE_PERIOD_MS;
        if (graceMs > 0) {
            logger.info("Waiting {}ms before cleanup for session {}", graceMs, sessionId);
            try {
                Thread.sleep(graceMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        CleanupResult result = cleanup(dockerClient, sessionId, executor);
        logLeftovers(sessionId, result);
        deletePortFile(sessionId);
        deleteJarFile(sessionId);
        return result;
    }

    /**
     * Returns the process exit status for a cleanup result.
     *
     * @param result the cleanup result
     * @return {@code 0} when no labeled resource remains, {@link #EXIT_LEFTOVERS} otherwise
     */
    static int exitCode(CleanupResult result) {
        return result.clean() ? 0 : EXIT_LEFTOVERS;
    }

    /**
     * Logs the resources that survived every sweep, with the label filter needed to find them.
     *
     * @param sessionId the session UUID
     * @param result the cleanup result
     */
    private static void logLeftovers(String sessionId, CleanupResult result) {
        if (result.clean()) {
            logger.info("Session {} cleanup complete after {} sweep(s)", sessionId, result.sweeps());
            return;
        }
        logger.error(
                "Session {} cleanup incomplete after {} sweep(s) (discoveryComplete={}): containers={} networks={}; "
                        + "resources remain labeled with {}={}",
                sessionId,
                result.sweeps(),
                result.discoveryComplete(),
                result.containerIds(),
                result.networkIds(),
                ResourceLabels.SESSION_ID,
                sessionId);
    }

    /**
     * Cleans up after a failed accept: deletes the discovery files if they are
     * still owned by this reaper, closes the socket, and exits.
     *
     * @param sessionId the session UUID
     * @param port this reaper's bound port
     * @param serverSocket the listening socket
     */
    private static void failAccept(String sessionId, int port, ServerSocket serverSocket) {
        deleteDiscoveryFilesIfOwned(sessionId, port);
        closeQuietly(serverSocket);
        System.exit(1);
    }

    /**
     * Closes the listening socket, ignoring errors.
     *
     * @param serverSocket the socket to close
     */
    private static void closeQuietly(ServerSocket serverSocket) {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Performs the session ID handshake with the core module, then enters a
     * command loop reading from the socket. Processes TERMINATE_CONTAINER,
     * TERMINATE_NETWORK, and TERMINATE commands. Returns when the connection
     * drops or TERMINATE is received.
     *
     * <p>Cleanup commands are enqueued to the executor for asynchronous
     * processing; the read loop never blocks on Docker calls.
     *
     * @param clientSocket the connected client socket
     * @param sessionId the expected session ID
     * @param handshakeTimeoutMilliseconds the handshake read timeout
     * @param executor the cleanup executor for async resource cleanup
     * @return {@code true} if the client sent TERMINATE before disconnecting
     * @throws IOException if the handshake or read fails
     */
    static boolean receiveSessionAndWatch(
            Socket clientSocket, String sessionId, int handshakeTimeoutMilliseconds, CleanupExecutor executor)
            throws IOException {
        clientSocket.setSoTimeout(handshakeTimeoutMilliseconds);
        var writer = new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8);
        var reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

        String handshakeLine = reader.readLine();
        if (!sessionId.equals(handshakeLine)) {
            throw new HandshakeException(
                    "Handshake mismatch: expected session " + sessionId + ", got '" + handshakeLine + "'");
        }

        writer.write("OK\n");
        writer.flush();
        logger.info("Connected");

        clientSocket.setSoTimeout(0);
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                return false; // connection dropped
            }
            if ("TERMINATE".equals(line)) {
                return true; // explicit terminate
            }
            if (line.startsWith("TERMINATE_NETWORK ")) {
                String networkId = line.substring(18).trim();
                executor.submit(new CleanupTask(networkId, CleanupTask.ResourceType.NETWORK, 0));
                continue;
            }
            if (line.startsWith("TERMINATE_CONTAINER ")) {
                String containerId = line.substring(20).trim();
                executor.submit(new CleanupTask(containerId, CleanupTask.ResourceType.CONTAINER, 0));
                continue;
            }
            logger.warn("Unknown command: {}", line);
        }
    }

    /**
     * Cleans up all Docker resources (containers and networks) labeled with
     * the given session ID, within {@link CleanupExecutor#DRAIN_DEADLINE_MINUTES}.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param executor the cleanup executor
     * @return the cleanup result
     */
    private static CleanupResult cleanup(DockerClient client, String sessionId, CleanupExecutor executor) {
        return cleanup(
                client,
                sessionId,
                executor,
                TimeUnit.MINUTES.toMillis(CleanupExecutor.DRAIN_DEADLINE_MINUTES),
                NETWORK_PHASE_RESERVE_MS);
    }

    /**
     * Removes every Docker resource labeled with the given session ID, then verifies that nothing is
     * left behind.
     *
     * <p>Cleanup runs in up to {@link #MAX_SWEEPS} sweeps. Each sweep has two phases: phase 1
     * force-removes every session container (the owning JVM is gone, so a graceful stop has no
     * consumer); phase 2 starts once container removal has drained (or the time left reaches the
     * network reserve), lists the session networks afresh, and removes them. Removing containers
     * first avoids "active endpoints" failures, and the reserve guarantees networks still get time
     * when container removal is slow.
     *
     * <p>A sweep that leaves resources unresolved is repeated after {@link #SWEEP_PAUSE_MS}, which
     * re-discovers the daemon's current state and restarts each resource's attempt ladder. Sweeping
     * stops as soon as the daemon shows nothing unresolved and discovery succeeded, after
     * {@link #MAX_SWEEPS}, or when the deadline expires. Resource listings are retried
     * ({@link #LIST_ATTEMPTS}) within a sweep and re-attempted by the next sweep; a listing that keeps
     * failing makes the run report incomplete discovery rather than appearing clean.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param executor the cleanup executor
     * @param drainMillis the overall drain deadline in milliseconds
     * @param networkReserveMillis the part of the deadline reserved for the network phase
     * @return the cleanup result, including any resources that survived every sweep
     */
    static CleanupResult cleanup(
            DockerClient client,
            String sessionId,
            CleanupExecutor executor,
            long drainMillis,
            long networkReserveMillis) {
        Map<String, String> filter = ResourceLabels.filterForSession(sessionId);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMillis);
        long pauseNanos = TimeUnit.MILLISECONDS.toNanos(SWEEP_PAUSE_MS);
        boolean lastDiscoveryComplete = true;
        int sweeps = 0;

        while (sweeps < MAX_SWEEPS && (sweeps == 0 || deadlineNanos - System.nanoTime() >= pauseNanos)) {
            sweeps++;
            logger.info("Session {} sweep {}/{}", sessionId, sweeps, MAX_SWEEPS);
            lastDiscoveryComplete = sweep(client, sessionId, executor, filter, deadlineNanos, networkReserveMillis);
            boolean settled = executor.unresolvedCount() == 0 && executor.pendingCount() == 0;
            if (settled && lastDiscoveryComplete) {
                break;
            }
            if (sweeps < MAX_SWEEPS && !pauseBeforeNextSweep(deadlineNanos)) {
                break;
            }
        }

        executor.shutdown();
        try {
            boolean completed =
                    executor.awaitTermination(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!completed) {
                logger.warn("drain incomplete; {} tasks abandoned", executor.getQueueSize());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Drain interrupted; {} tasks may remain", executor.getQueueSize());
            executor.shutdownNow();
        }

        return verify(client, sessionId, executor, sweeps, lastDiscoveryComplete);
    }

    /**
     * Runs one sweep: force-remove every session container, wait for that phase to drain within the
     * container budget, then list the session networks afresh and remove them.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param executor the cleanup executor
     * @param filter the session label filter
     * @param deadlineNanos the sweep deadline in {@link System#nanoTime()} units
     * @param networkReserveMillis the part of the deadline reserved for the network phase
     * @return {@code true} if both phases were discovered successfully
     */
    private static boolean sweep(
            DockerClient client,
            String sessionId,
            CleanupExecutor executor,
            Map<String, String> filter,
            long deadlineNanos,
            long networkReserveMillis) {
        boolean discoveryComplete = true;

        List<String> containerIds = listContainers(client, sessionId, filter);
        if (containerIds == null) {
            discoveryComplete = false;
        } else {
            for (String id : containerIds) {
                executor.submit(new CleanupTask(id, CleanupTask.ResourceType.CONTAINER, 0, true));
            }
            logger.info("Session cleanup enqueued (containers={})", containerIds.size());
        }

        long containerBudgetNanos =
                deadlineNanos - System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(networkReserveMillis);
        if (!awaitIdle(executor, Math.max(0L, containerBudgetNanos), "Container removal before network phase")) {
            logger.warn("Container removal incomplete before network phase; {} tasks pending", executor.getQueueSize());
        }

        List<String> networkIds = listNetworks(client, sessionId, filter);
        if (networkIds == null) {
            discoveryComplete = false;
        } else {
            for (String id : networkIds) {
                executor.submit(new CleanupTask(id, CleanupTask.ResourceType.NETWORK, 0));
            }
            logger.info("Session cleanup enqueued (networks={})", networkIds.size());
        }

        if (!awaitIdle(executor, deadlineNanos - System.nanoTime(), "Network removal")) {
            logger.warn("Network removal incomplete; {} tasks pending", executor.getQueueSize());
        }
        return discoveryComplete;
    }

    /**
     * Waits until no submitted or retried cleanup task is pending.
     *
     * @param executor the cleanup executor
     * @param budgetNanos the maximum time to wait in nanoseconds
     * @param phase the phase name used in the interrupt log message
     * @return {@code true} if the executor went idle within the budget
     */
    private static boolean awaitIdle(CleanupExecutor executor, long budgetNanos, String phase) {
        try {
            return executor.awaitIdle(Math.max(0L, budgetNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("{} interrupted; {} tasks may remain", phase, executor.getQueueSize());
            return false;
        }
    }

    /**
     * Pauses between sweeps so the daemon can settle in-flight removals.
     *
     * @param deadlineNanos the sweep deadline in {@link System#nanoTime()} units
     * @return {@code false} if the pause was interrupted or no budget remains
     */
    private static boolean pauseBeforeNextSweep(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return false;
        }
        try {
            Thread.sleep(Math.min(SWEEP_PAUSE_MS, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Sweep pause interrupted; stopping further sweeps");
            return false;
        }
    }

    /**
     * Lists the containers labeled for the session, retrying transient daemon failures.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param filter the session label filter
     * @return the container ids, or {@code null} if the listing failed after every attempt
     */
    private static List<String> listContainers(DockerClient client, String sessionId, Map<String, String> filter) {
        for (int attempt = 1; attempt <= LIST_ATTEMPTS; attempt++) {
            try {
                return client.listContainersCmd().withShowAll(true).withLabelFilter(filter).exec().stream()
                        .map(Container::getId)
                        .toList();
            } catch (RuntimeException e) {
                logger.warn(
                        "Failed to list containers for session {} (attempt {}/{}): {}",
                        sessionId,
                        attempt,
                        LIST_ATTEMPTS,
                        e.getMessage());
                if (attempt == LIST_ATTEMPTS || !pauseBeforeRetry(attempt)) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Lists the networks labeled for the session, retrying transient daemon failures.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param filter the session label filter
     * @return the network ids, or {@code null} if the listing failed after every attempt
     */
    private static List<String> listNetworks(DockerClient client, String sessionId, Map<String, String> filter) {
        List<String> filterValues = filter.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        for (int attempt = 1; attempt <= LIST_ATTEMPTS; attempt++) {
            try {
                return client.listNetworksCmd().withFilter("label", filterValues).exec().stream()
                        .map(network -> network.getId())
                        .toList();
            } catch (RuntimeException e) {
                logger.warn(
                        "Failed to list networks for session {} (attempt {}/{}): {}",
                        sessionId,
                        attempt,
                        LIST_ATTEMPTS,
                        e.getMessage());
                if (attempt == LIST_ATTEMPTS || !pauseBeforeRetry(attempt)) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Sleeps before the next discovery attempt.
     *
     * @param failedAttempt the 1-based index of the failed attempt
     * @return {@code false} if the sleep was interrupted
     */
    private static boolean pauseBeforeRetry(int failedAttempt) {
        try {
            Thread.sleep(LIST_RETRY_DELAY_MS * failedAttempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Produces the cleanup result, paying for an authoritative listing only when something is still
     * unresolved.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     * @param executor the cleanup executor
     * @param sweeps the number of sweeps that ran
     * @param discoveryComplete whether every discovery in this run succeeded
     * @return the cleanup result
     */
    private static CleanupResult verify(
            DockerClient client, String sessionId, CleanupExecutor executor, int sweeps, boolean discoveryComplete) {
        if (executor.unresolvedCount() == 0) {
            return new CleanupResult(sweeps, discoveryComplete, List.of(), List.of());
        }
        Map<String, String> filter = ResourceLabels.filterForSession(sessionId);
        List<String> remainingContainers = listContainers(client, sessionId, filter);
        List<String> remainingNetworks = listNetworks(client, sessionId, filter);
        boolean complete = discoveryComplete && remainingContainers != null && remainingNetworks != null;
        return new CleanupResult(
                sweeps,
                complete,
                remainingContainers != null
                        ? remainingContainers
                        : executor.unresolvedIds(CleanupTask.ResourceType.CONTAINER),
                remainingNetworks != null
                        ? remainingNetworks
                        : executor.unresolvedIds(CleanupTask.ResourceType.NETWORK));
    }

    /**
     * Creates a Docker client from the system configuration.
     *
     * @return a new Docker client
     */
    private static DockerClient createDockerClient() {
        var config = DockerHostConfig.createDockerClientConfig();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * Outcome of a session cleanup: how many sweeps ran, whether discovery succeeded, and which
     * labeled resources could not be removed.
     *
     * @param sweeps the number of sweeps that ran
     * @param discoveryComplete whether every resource listing succeeded
     * @param containerIds the labeled containers still present, sorted
     * @param networkIds the labeled networks still present, sorted
     */
    record CleanupResult(int sweeps, boolean discoveryComplete, List<String> containerIds, List<String> networkIds) {

        /**
         * Validates and sorts the leftover id lists.
         *
         * @param sweeps the number of sweeps that ran
         * @param discoveryComplete whether every resource listing succeeded
         * @param containerIds the labeled containers still present
         * @param networkIds the labeled networks still present
         */
        CleanupResult {
            containerIds = List.copyOf(containerIds).stream().sorted().toList();
            networkIds = List.copyOf(networkIds).stream().sorted().toList();
        }

        /**
         * Returns whether the session left nothing behind and left no discovery unanswered.
         *
         * @return {@code true} if the cleanup is complete
         */
        boolean clean() {
            return discoveryComplete && containerIds.isEmpty() && networkIds.isEmpty();
        }

        /**
         * Returns the result used on paths that terminate the process before cleanup runs.
         *
         * @return an incomplete result
         */
        static CleanupResult incomplete() {
            return new CleanupResult(0, false, List.of(), List.of());
        }
    }

    /**
     * Thrown when the reaper handshake fails due to session ID mismatch.
     */
    private static final class HandshakeException extends IOException {

        /**
         * Creates a handshake exception.
         *
         * @param message the detail message
         */
        private HandshakeException(String message) {
            super(message);
        }
    }

    /**
     * Writes the reaper's listening port to the discovery file.
     *
     * @param port the listening port
     * @param sessionId the session UUID
     * @throws IOException if the file cannot be written
     */
    private static void writePortFile(int port, String sessionId) throws IOException {
        Path path = portFilePath(sessionId);
        Files.writeString(path, String.valueOf(port), StandardCharsets.UTF_8);
    }

    /**
     * Deletes the port discovery file, ignoring errors.
     *
     * @param sessionId the session UUID
     */
    private static void deletePortFile(String sessionId) {
        deleteQuietly(portFilePath(sessionId));
    }

    /**
     * Deletes this reaper's discovery files only if the port file still
     * contains this reaper's port. A relaunched reaper for the same session
     * overwrites the port file; without this guard, a stale reaper timing out
     * would delete the new reaper's port file (and possibly its JAR).
     *
     * @param sessionId the session UUID
     * @param port this reaper's bound port
     * @return {@code true} if the files were deleted, {@code false} if the
     *     port file was missing or owned by another reaper
     */
    static boolean deleteDiscoveryFilesIfOwned(String sessionId, int port) {
        boolean owned;
        try {
            owned = String.valueOf(port)
                    .equals(Files.readString(portFilePath(sessionId), StandardCharsets.UTF_8)
                            .trim());
        } catch (IOException e) {
            owned = false;
        }
        if (!owned) {
            logger.warn(
                    "Port file for session {} no longer advertises port {}; skipping discovery file cleanup",
                    sessionId,
                    port);
            return false;
        }
        deletePortFile(sessionId);
        deleteJarFile(sessionId);
        return true;
    }

    /**
     * Returns the path to the port discovery file for a session.
     *
     * @param sessionId the session UUID
     * @return the port file path
     */
    private static Path portFilePath(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), sessionFileName(sessionId, PORT_EXTENSION));
    }

    /**
     * Cleans up resources after a handshake failure: deletes discovery files
     * (only if still owned by this reaper), closes the client socket, and
     * shuts down the executor.
     *
     * @param sessionId the session UUID
     * @param port this reaper's bound port
     * @param clientSocket the connected client socket
     * @param executor the cleanup executor
     */
    static void cleanupOnHandshakeFailure(String sessionId, int port, Socket clientSocket, CleanupExecutor executor) {
        deleteDiscoveryFilesIfOwned(sessionId, port);
        try {
            clientSocket.close();
        } catch (IOException ignored) {
            // Best-effort close after failed handshake.
        }
        executor.shutdownNow();
    }

    /**
     * Deletes the reaper JAR file from the temp directory, ignoring errors.
     *
     * @param sessionId the session UUID
     */
    private static void deleteJarFile(String sessionId) {
        deleteQuietly(Paths.get(System.getProperty("java.io.tmpdir"), sessionFileName(sessionId, JAR_EXTENSION)));
    }

    /**
     * Deletes a file if it exists, ignoring errors.
     *
     * @param path the file to delete
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns the per-session file name used for the reaper's discovery, log,
     * and JAR files.
     *
     * @param sessionId the session UUID
     * @param extension the file extension, including the leading dot
     * @return the file name
     */
    private static String sessionFileName(String sessionId, String extension) {
        return "altcontainers-reaper-" + sessionId + extension;
    }

    /**
     * Configures Logback to write rolling log files to the given path.
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
        triggeringPolicy.setMaxFileSize(FileSize.valueOf("1MB"));
        triggeringPolicy.setContext(context);

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setFileNamePattern(logPath + ".%i");
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(9);
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(appender);

        try {
            triggeringPolicy.start();
            rollingPolicy.start();
            appender.setTriggeringPolicy(triggeringPolicy);
            appender.setRollingPolicy(rollingPolicy);
            appender.start();
        } catch (RuntimeException e) {
            System.err.println("Reaper: Failed to configure logback file logging: " + e.getMessage());
            return;
        }

        Level level = Level.toLevel(System.getProperty("altcontainers.reaper.log.level", "INFO"), Level.INFO);
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(level);
        rootLogger.addAppender(appender);
    }

    /**
     * Logs the reaper startup banner with version, PID, and session ID
     * each on its own line.
     *
     * @param sessionId the session UUID
     */
    static void logHeader(String sessionId) {
        logger.info("Altcontainers Reaper v{}", Version.version());
        logger.info("PID {}", ProcessHandle.current().pid());
        logger.info("Session {}", sessionId);
    }
}
