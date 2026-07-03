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

package nonapi.org.altcontainers;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.altcontainers.api.ContainerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log-related Docker operations.
 *
 * <p>Package-private static utility. Every method takes {@link DockerClient} as the first argument
 * to access the configured Docker HTTP client. Log-stream tracking and {@link LogStreamHandle}
 * wrapping stay in {@link DockerClient#attachLogStream}.
 */
public final class LogOperations {

    private static final Logger logger = LoggerFactory.getLogger(LogOperations.class);

    private LogOperations() {
        // Intentionally empty
    }

    /**
     * Attaches a raw follow-stream to a container's combined stdout/stderr.
     *
     * <p>Returns the raw {@link Closeable} from the HTTP client. The caller
     * ({@link DockerClient#attachLogStream}) wraps it in a {@link LogStreamHandle} and
     * registers it for lifecycle management. This method does NOT touch the log-stream map.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @param displayLineConsumer receives stripped log lines; {@code null} treated as a no-op
     * @param rawLineConsumer receives raw (newline-terminated) log lines; {@code null} treated as a no-op
     * @return a closeable for the raw follow-stream
     * @throws IllegalArgumentException if {@code id} is blank
     * @throws ContainerException if Docker fails to attach the log stream
     */
    public static Closeable attachStream(
            DockerClient client, String id, Consumer<String> displayLineConsumer, Consumer<String> rawLineConsumer) {
        DockerClient.requireNonBlank(id, "id");
        Consumer<String> display = displayLineConsumer != null ? displayLineConsumer : line -> {};
        Consumer<String> raw = rawLineConsumer != null ? rawLineConsumer : line -> {};
        LineBuffer lineBuffer = new LineBuffer();
        try {
            return client.delegate().tailLogs(id, payload -> {
                lineBuffer.append(payload);
                lineBuffer.drainLines(display, raw);
            });
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to attach log stream for container " + id, e);
        }
    }

    /**
     * Returns existing stdout/stderr logs from a container as a combined string.
     *
     * @param client the Docker client
     * @param id the container identifier; must not be blank
     * @return the combined log output, or an empty string if logs could not be retrieved
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static String getLogs(DockerClient client, String id) {
        DockerClient.requireNonBlank(id, "id");
        try {
            return client.delegate().getContainerLogs(id);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Byte-buffer that accumulates log frames and reassembles them into lines across frame
     * boundaries.
     */
    private static final class LineBuffer {

        private static final int INITIAL_CAPACITY = 8192;

        /** Maximum buffer size in bytes to prevent unbounded growth with newline-free payloads. */
        private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024;

        private byte[] data;
        private int writePos;

        LineBuffer() {
            this.data = new byte[INITIAL_CAPACITY];
            this.writePos = 0;
        }

        void append(byte[] payload) {
            if (writePos + payload.length > MAX_BUFFER_SIZE) {
                // Discard all buffered data: a line this large will never be useful
                // as a log message and risks OOM. Log a warning so operators have
                // diagnostic context when log-based wait strategies fail to match.
                logger.warn(
                        "Log line buffer overflow: discarding {} buffered bytes (payload={}, max={})",
                        writePos,
                        payload.length,
                        MAX_BUFFER_SIZE);
                writePos = 0;
                return;
            }
            ensureCapacity(payload.length);
            System.arraycopy(payload, 0, data, writePos, payload.length);
            writePos += payload.length;
        }

        void drainLines(Consumer<String> display, Consumer<String> raw) {
            int lineStart = 0;
            for (int i = 0; i < writePos; i++) {
                if (data[i] == '\n') {
                    int lineLen = i - lineStart + 1;
                    String rawLine = new String(data, lineStart, lineLen, StandardCharsets.UTF_8);
                    String stripped = rawLine.endsWith("\n") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                    if (stripped.endsWith("\r")) {
                        stripped = stripped.substring(0, stripped.length() - 1);
                    }
                    try {
                        raw.accept(rawLine);
                    } catch (RuntimeException e) {
                        // Raw consumer failure must not kill the log-stream daemon thread.
                        logger.warn("Log raw consumer threw exception", e);
                    }
                    if (!stripped.isBlank()) {
                        try {
                            display.accept(stripped);
                        } catch (RuntimeException e) {
                            // Display consumer failure must not prevent raw-line dispatch
                            // to wait strategies.
                            logger.warn("Log display consumer threw exception", e);
                        }
                    }
                    lineStart = i + 1;
                }
            }
            if (lineStart > 0) {
                int remaining = writePos - lineStart;
                if (remaining > 0) {
                    System.arraycopy(data, lineStart, data, 0, remaining);
                }
                writePos = remaining;
            }
        }

        private void ensureCapacity(int additional) {
            int required = writePos + additional;
            if (required > data.length) {
                int newCapacity = Math.max(data.length * 2, required);
                byte[] newData = new byte[newCapacity];
                System.arraycopy(data, 0, newData, 0, writePos);
                data = newData;
            }
        }
    }
}
