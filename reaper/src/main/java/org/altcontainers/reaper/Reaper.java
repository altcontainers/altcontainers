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
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaper — standalone watchdog process for Docker resource cleanup.
 *
 * <p>Launched by the core module. Watches a single persistent TCP
 * connection for liveness. When the connection drops, cleans up all
 * Docker resources labeled with the session ID.
 */
public final class Reaper {

    private static final Logger logger = LoggerFactory.getLogger(Reaper.class);
    private static final long GRACE_PERIOD_MS = 30_000L;
    private static final int STOP_TIMEOUT_MS = parseStopTimeoutMsFromProperty();
    private static final int HANDSHAKE_TIMEOUT_MILLISECONDS =
            parsePositiveMillisecondsProperty("altcontainers.reaper.connection.timeout.ms", "10000");

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
            System.err.println("Reaper: " + e.getMessage());
            System.exit(1);
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
        return logDirectory + File.separator + "altcontainers-reaper-" + sessionId + ".log";
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
            run(sessionId, dockerClient);
            System.exit(0);
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

    private static final long ACCEPT_TIMEOUT_MS = 60_000L;

    /**
     * Runs the reaper lifecycle: binds a server socket, writes the port file,
     * accepts a connection, performs the session handshake, watches for
     * liveness, and cleans up resources on disconnect or termination.
     *
     * @param sessionId the session UUID
     * @param dockerClient the Docker client
     */
    private static void run(String sessionId, DockerClient dockerClient) {

        ServerSocket serverSocket;
        int port;
        try {
            serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getByName("localhost"));
            serverSocket.setSoTimeout((int) ACCEPT_TIMEOUT_MS);
            port = serverSocket.getLocalPort();
        } catch (IOException e) {
            logger.error("Failed to bind server socket: {}", e.getMessage());
            System.exit(1);
            return;
        }

        try {
            writePortFile(port, sessionId);
        } catch (IOException e) {
            logger.error("Failed to write port file: {}", e.getMessage());
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            System.exit(1);
            return;
        }
        logger.info("Port {}", port);

        Socket clientSocket;
        try {
            clientSocket = serverSocket.accept();
            serverSocket.close();
        } catch (SocketTimeoutException e) {
            logger.error("No connection received within {}ms timeout", ACCEPT_TIMEOUT_MS);
            deletePortFile(sessionId);
            deleteJarFile(sessionId);
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            System.exit(1);
            return;
        } catch (IOException e) {
            logger.error("Accept failed: {}", e.getMessage());
            deletePortFile(sessionId);
            deleteJarFile(sessionId);
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            System.exit(1);
            return;
        }

        boolean receivedTerminate;
        try {
            receivedTerminate =
                    receiveSessionAndWatch(clientSocket, sessionId, HANDSHAKE_TIMEOUT_MILLISECONDS, dockerClient);
            logger.info("Disconnected (terminate={})", receivedTerminate);
        } catch (HandshakeException e) {
            logger.warn(e.getMessage());
            deletePortFile(sessionId);
            try {
                clientSocket.close();
            } catch (IOException ignored) {
                // Best-effort close before exiting after failed handshake.
            }
            System.exit(1);
            return;
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

        cleanup(dockerClient, sessionId);
        deletePortFile(sessionId);
        deleteJarFile(sessionId);
    }

    /**
     * Performs the session ID handshake with the core module, then enters a
     * command loop reading from the socket. Processes TERMINATE_CONTAINER,
     * TERMINATE_NETWORK, and TERMINATE commands. Returns when the connection
     * drops or TERMINATE is received.
     *
     * @param clientSocket the connected client socket
     * @param sessionId the expected session ID
     * @param handshakeTimeoutMilliseconds the handshake read timeout
     * @param dockerClient the Docker client used for per-resource cleanup
     * @return {@code true} if the client sent TERMINATE before disconnecting
     * @throws IOException if the handshake or read fails
     */
    static boolean receiveSessionAndWatch(
            Socket clientSocket, String sessionId, int handshakeTimeoutMilliseconds, DockerClient dockerClient)
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
                terminateNetworkSingle(dockerClient, networkId);
                continue;
            }
            if (line.startsWith("TERMINATE_CONTAINER ")) {
                String containerId = line.substring(20).trim();
                stopAndRemoveSingle(dockerClient, containerId);
                continue;
            }
            logger.warn("Unknown command: {}", line);
        }
    }

    /**
     * Stops and removes a single container. Non-existent containers are treated
     * as success (already gone).
     *
     * @param client the Docker client
     * @param containerId the container ID
     */
    private static void stopAndRemoveSingle(DockerClient client, String containerId) {
        try {
            client.stopContainerCmd(containerId)
                    .withTimeout((int) (STOP_TIMEOUT_MS / 1000L))
                    .exec();
        } catch (NotFoundException e) {
            logger.debug("Container {} not found during stop (already removed)", containerId);
            return;
        } catch (NotModifiedException e) {
            logger.debug("Container {} already stopped (Status 304)", containerId);
        } catch (RuntimeException e) {
            logger.warn("Failed to stop container {}: {}", containerId, e.getMessage());
        }
        try {
            client.removeContainerCmd(containerId).withForce(true).exec();
            logger.info("Removed container {}", containerId);
        } catch (NotFoundException e) {
            logger.debug("Container {} not found during remove (already removed)", containerId);
        } catch (NotModifiedException e) {
            logger.debug("Container {} already removed (Status 304)", containerId);
        } catch (RuntimeException e) {
            logger.error("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * Removes a single network. Non-existent networks are treated as success
     * (already gone).
     *
     * @param client the Docker client
     * @param networkId the network ID
     */
    private static void terminateNetworkSingle(DockerClient client, String networkId) {
        try {
            client.removeNetworkCmd(networkId).exec();
            logger.info("Removed network {}", networkId);
        } catch (NotFoundException e) {
            logger.debug("Network {} not found (already removed)", networkId);
        } catch (NotModifiedException e) {
            logger.debug("Network {} already removed (Status 304)", networkId);
        } catch (RuntimeException e) {
            logger.error("Failed to remove network {}: {}", networkId, e.getMessage());
        }
    }

    /**
     * Cleans up all Docker resources (containers and networks) labeled with
     * the given session ID.
     *
     * @param client the Docker client
     * @param sessionId the session UUID
     */
    private static void cleanup(DockerClient client, String sessionId) {
        Map<String, String> filter = ResourceLabels.filterForSession(sessionId);

        // Phase 1: Containers
        List<String> containerIds = List.of();
        try {
            containerIds = client.listContainersCmd().withShowAll(true).withLabelFilter(filter).exec().stream()
                    .map(Container::getId)
                    .toList();
        } catch (RuntimeException e) {
            logger.error("Failed to list containers for session {}: {}", sessionId, e.getMessage());
        }

        for (String id : containerIds) {
            try {
                client.stopContainerCmd(id)
                        .withTimeout((int) (STOP_TIMEOUT_MS / 1000L))
                        .exec();
            } catch (NotFoundException e) {
                logger.debug("Container {} not found during stop (already removed)", id);
                continue;
            } catch (NotModifiedException e) {
                logger.debug("Container {} already stopped (Status 304)", id);
            } catch (RuntimeException e) {
                logger.warn("Failed to stop container {}: {}", id, e.getMessage());
            }
            try {
                client.removeContainerCmd(id).withForce(true).exec();
                logger.info("Removed container {}", id);
            } catch (NotFoundException e) {
                logger.debug("Container {} not found during remove (already removed)", id);
            } catch (NotModifiedException e) {
                logger.debug("Container {} already removed (Status 304)", id);
            } catch (RuntimeException e) {
                logger.error("Failed to remove container {}: {}", id, e.getMessage());
            }
        }

        // Phase 2: Networks
        List<String> networkIds = List.of();
        try {
            List<String> filterValues = filter.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .toList();
            networkIds = client.listNetworksCmd().withFilter("label", filterValues).exec().stream()
                    .map(n -> n.getId())
                    .toList();
        } catch (RuntimeException e) {
            logger.error("Failed to list networks for session {}: {}", sessionId, e.getMessage());
        }

        for (String id : networkIds) {
            try {
                client.removeNetworkCmd(id).exec();
                logger.info("Removed network {}", id);
            } catch (NotFoundException e) {
                logger.debug("Network {} not found (already removed)", id);
            } catch (RuntimeException e) {
                logger.error("Failed to remove network {}: {}", id, e.getMessage());
            }
        }

        logger.info("Session cleaned (networks={},containers={})", networkIds.size(), containerIds.size());
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
        try {
            Files.deleteIfExists(portFilePath(sessionId));
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns the path to the port discovery file for a session.
     *
     * @param sessionId the session UUID
     * @return the port file path
     */
    private static Path portFilePath(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".port");
    }

    /**
     * Deletes the reaper JAR file from the temp directory, ignoring errors.
     *
     * @param sessionId the session UUID
     */
    private static void deleteJarFile(String sessionId) {
        try {
            Files.deleteIfExists(
                    Paths.get(System.getProperty("java.io.tmpdir"), "altcontainers-reaper-" + sessionId + ".jar"));
        } catch (IOException ignored) {
        }
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
