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

package nonapi.org.altcontainers.docker;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Parses the Docker multiplexed log stream format from a container's {@code /logs} endpoint.
 *
 * <p>Docker's non-TTY stream consists of:
 *
 * <ul>
 *   <li>8-byte header: stream type (1 byte), padding (3 bytes), payload length (4 bytes, big-endian).
 *   <li>Payload of exactly {@code payloadLength} bytes.
 * </ul>
 *
 * <p>This parser reads frames from the stream and delivers payloads to the provided consumer.
 * It is not thread-safe; callers must synchronize access.
 */
public final class DockerLogFrameParser {

    private static final int HEADER_SIZE = 8;

    /**
     * Parses multiplexed log frames from the input stream until EOF.
     *
     * <p>Each frame's payload is delivered to {@code payloadConsumer}. Frames with zero-length
     * payloads are ignored.
     *
     * @param input the input stream to read from
     * @param payloadConsumer receives each non-empty payload; must not be {@code null}
     * @throws IOException if the stream cannot be read or contains a truncated frame
     */
    public void parse(InputStream input, Consumer<byte[]> payloadConsumer) throws IOException {
        if (payloadConsumer == null) {
            throw new IllegalArgumentException("payloadConsumer must not be null");
        }
        byte[] header = new byte[HEADER_SIZE];
        while (true) {
            int headerBytes = readFully(input, header, 0, HEADER_SIZE);
            if (headerBytes == 0) {
                return; // EOF at frame boundary
            }
            if (headerBytes < HEADER_SIZE) {
                throw new IOException("Truncated Docker log frame: expected " + HEADER_SIZE + "-byte header but read "
                        + headerBytes + " bytes");
            }
            int payloadLength = ((header[4] & 0xFF) << 24)
                    | ((header[5] & 0xFF) << 16)
                    | ((header[6] & 0xFF) << 8)
                    | (header[7] & 0xFF);
            if (payloadLength == 0) {
                continue;
            }
            byte[] payload = new byte[payloadLength];
            int payloadBytes = readFully(input, payload, 0, payloadLength);
            if (payloadBytes < payloadLength) {
                throw new IOException("Truncated Docker log frame: expected " + payloadLength
                        + "-byte payload but read " + payloadBytes + " bytes");
            }
            payloadConsumer.accept(payload);
        }
    }

    /**
     * Reads exactly {@code len} bytes into {@code buf} starting at {@code off}, returning the number
     * of bytes actually read. Returns 0 only on EOF when no bytes have been read.
     */
    private static int readFully(InputStream input, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int read = input.read(buf, off + total, len - total);
            if (read < 0) {
                return total;
            }
            total += read;
        }
        return total;
    }
}
