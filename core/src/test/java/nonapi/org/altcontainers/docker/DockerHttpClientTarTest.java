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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DockerHttpClient#createMinimalTar(String, byte[], int)} and its size/name
 * guards (Findings 2 and 11).
 */
class DockerHttpClientTarTest {

    @Test
    void createMinimalTarRejectsNameLongerThan100Bytes() {
        String name = "a".repeat(101);

        assertThatThrownBy(() -> DockerHttpClient.createMinimalTar(name, new byte[0], 0644))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName too long");
    }

    @Test
    void createMinimalTarAcceptsNameExactly100Bytes() {
        String name = "a".repeat(100);
        byte[] content = "hi\n".getBytes(StandardCharsets.UTF_8);

        byte[] tar = DockerHttpClient.createMinimalTar(name, content, 0644);

        // Name field (bytes 0..99) is the full 100-byte name; byte 100 is the start of the mode field.
        assertThat(new String(tar, 0, 100, StandardCharsets.UTF_8)).isEqualTo(name);
    }

    @Test
    void createMinimalTarWritesNameSizeModeAndValidChecksum() {
        byte[] content = "hello\n".getBytes(StandardCharsets.UTF_8);
        int mode = 0644;

        byte[] tar = DockerHttpClient.createMinimalTar("hello.txt", content, mode);

        // Header is the first 512 bytes.
        // Name field (NUL-terminated within 100 bytes).
        String name = new String(tar, 0, 100, StandardCharsets.UTF_8).split("\0", 2)[0];
        assertThat(name).isEqualTo("hello.txt");
        // Size field at offset 124, 12 bytes, octal NUL-terminated.
        assertThat(parseOctal(tar, 124, 12)).isEqualTo(content.length);
        // Mode field at offset 100, 8 bytes, octal.
        assertThat(parseOctal(tar, 100, 8)).isEqualTo(mode);
        // typeflag at offset 156 is '0' (regular file).
        assertThat((char) tar[156]).isEqualTo('0');
        // Checksum field at offset 148, 7 octal digits (byte 155 is a space) must be valid.
        long stored = parseOctal(tar, 148, 7);
        long recomputed = computeChecksum(tar, 0);
        assertThat(stored).isEqualTo(recomputed);
    }

    @Test
    void requireValidContentSizeRejectsOverflowingLength() {
        // A content length near Integer.MAX_VALUE would overflow the block-padding arithmetic
        // (length + 511). The guard must reject it without allocating a multi-GiB array.
        assertThatThrownBy(() -> DockerHttpClient.requireValidContentSize(Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content too large");
    }

    @Test
    void maxTarContentSizeIsBelowIntegerMax() {
        assertThat(DockerHttpClient.MAX_TAR_CONTENT_SIZE).isLessThan(Integer.MAX_VALUE);
        assertThat(DockerHttpClient.MAX_TAR_NAME_BYTES).isEqualTo(100);
    }

    @Test
    void maxTarContentSizeDoesNotOverflowAllocation() {
        int max = DockerHttpClient.MAX_TAR_CONTENT_SIZE;
        // Replicate the exact allocation arithmetic from createMinimalTar.
        int blockSize = 512;
        int contentBlocks = (max + blockSize - 1) / blockSize;
        int contentPadded = contentBlocks * blockSize;
        // Use long to detect overflow in the total allocation.
        long total = (long) blockSize + contentPadded + (long) blockSize * 2;
        assertThat(total)
                .as("Total byte-array allocation must not exceed Integer.MAX_VALUE")
                .isLessThanOrEqualTo((long) Integer.MAX_VALUE);
    }

    @Test
    void createMinimalTarRejectsNegativeMode() {
        assertThatThrownBy(() -> DockerHttpClient.createMinimalTar("file", new byte[0], -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    private static long parseOctal(byte[] buf, int off, int fieldLen) {
        String s = new String(buf, off, fieldLen, StandardCharsets.UTF_8).trim().replace("\0", "");
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    /** Recomputes the ustar checksum: sum of all 512 header bytes with the checksum field as spaces. */
    private static long computeChecksum(byte[] tar, int off) {
        byte[] header = new byte[512];
        System.arraycopy(tar, off, header, 0, 512);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b < 0 ? b + 256 : b;
        }
        return sum;
    }
}
