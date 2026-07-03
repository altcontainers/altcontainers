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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerLogFrameParserTest {

    private final DockerLogFrameParser parser = new DockerLogFrameParser();

    @Test
    void emitsPayloadsFromStdoutAndStderrFrames() throws IOException {
        // Frame 1: stdout (type=1), payload "hello\n"
        // Frame 2: stderr (type=2), payload "error\n"
        byte[] frame1 = buildFrame((byte) 1, "hello\n");
        byte[] frame2 = buildFrame((byte) 2, "error\n");
        byte[] data = concat(frame1, frame2);

        List<String> payloads = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(data), bytes -> payloads.add(new String(bytes, StandardCharsets.UTF_8)));

        assertThat(payloads).containsExactly("hello\n", "error\n");
    }

    @Test
    void handlesFramesSplitAcrossReads() throws IOException {
        byte[] frame = buildFrame((byte) 1, "hello\n");

        // Use a Byte-at-a-time input stream wrapper to simulate split reads.
        InputStream slowInput = new InputStream() {
            private int pos = 0;

            @Override
            public int read() throws IOException {
                if (pos >= frame.length) return -1;
                return frame[pos++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (pos >= frame.length) return -1;
                int toRead = Math.min(1, len); // only read 1 byte at a time
                int avail = Math.min(toRead, frame.length - pos);
                System.arraycopy(frame, pos, b, off, avail);
                pos += avail;
                return avail;
            }
        };

        List<String> payloads = new ArrayList<>();
        parser.parse(slowInput, bytes -> payloads.add(new String(bytes, StandardCharsets.UTF_8)));

        assertThat(payloads).containsExactly("hello\n");
    }

    @Test
    void ignoresZeroLengthFrames() throws IOException {
        byte[] zeroFrame = buildFrame((byte) 1, "");
        byte[] realFrame = buildFrame((byte) 1, "hello\n");
        byte[] data = concat(zeroFrame, realFrame);

        List<String> payloads = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(data), bytes -> payloads.add(new String(bytes, StandardCharsets.UTF_8)));

        assertThat(payloads).containsExactly("hello\n");
    }

    @Test
    void throwsOnTruncatedHeader() {
        byte[] truncated = new byte[] {1, 0, 0, 0}; // only 4 bytes of header

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(truncated), bytes -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Truncated Docker log frame");
    }

    @Test
    void throwsOnTruncatedPayload() {
        byte[] header = new byte[] {1, 0, 0, 0, 0, 0, 0, 20}; // 20-byte payload
        byte[] incompletePayload = new byte[] {1, 2, 3}; // only 3 bytes
        byte[] data = concat(header, incompletePayload);

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(data), bytes -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Truncated Docker log frame");
    }

    @Test
    void rejectsNullPayloadConsumer() {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(new byte[0]), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadConsumer must not be null");
    }

    @Test
    void emptyStreamEmitsNothing() throws IOException {
        List<String> payloads = new ArrayList<>();
        parser.parse(
                new ByteArrayInputStream(new byte[0]),
                bytes -> payloads.add(new String(bytes, StandardCharsets.UTF_8)));
        assertThat(payloads).isEmpty();
    }

    private static byte[] buildFrame(byte streamType, String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int len = payloadBytes.length;
        byte[] frame = new byte[8 + len];
        frame[0] = streamType;
        frame[4] = (byte) (len >> 24);
        frame[5] = (byte) (len >> 16);
        frame[6] = (byte) (len >> 8);
        frame[7] = (byte) len;
        System.arraycopy(payloadBytes, 0, frame, 8, len);
        return frame;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
