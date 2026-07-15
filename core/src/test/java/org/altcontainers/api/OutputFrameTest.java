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

package org.altcontainers.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OutputFrame}.
 */
class OutputFrameTest {

    @Test
    void constructorShouldCopyInputBytes() {
        byte[] input = {0x00, 0x01, 0x02};
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, input);

        assertThat(frame.type()).isEqualTo(OutputFrame.Type.STDOUT);
        assertThat(frame.bytes()).containsExactly(0x00, 0x01, 0x02);

        // Mutate original input; frame should be unaffected
        input[0] = (byte) 0xFF;
        assertThat(frame.bytes()).containsExactly(0x00, 0x01, 0x02);
    }

    @Test
    void bytesAccessorShouldReturnDefensiveCopy() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x01, 0x02});

        byte[] first = frame.bytes();
        byte[] second = frame.bytes();

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotSameAs(second);

        // Mutate one; the other should be unaffected
        first[0] = (byte) 0xFF;
        assertThat(second[0]).isEqualTo((byte) 0x01);
    }

    @Test
    void typeShouldReturnStdout() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[0]);
        assertThat(frame.type()).isEqualTo(OutputFrame.Type.STDOUT);
    }

    @Test
    void typeShouldReturnStderr() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDERR, new byte[0]);
        assertThat(frame.type()).isEqualTo(OutputFrame.Type.STDERR);
    }

    @Test
    void typeShouldReturnRaw() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.RAW, new byte[0]);
        assertThat(frame.type()).isEqualTo(OutputFrame.Type.RAW);
    }

    @Test
    void typeShouldReturnUnknown() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.UNKNOWN, new byte[0]);
        assertThat(frame.type()).isEqualTo(OutputFrame.Type.UNKNOWN);
    }

    @Test
    void utf8StringShouldDecodeContent() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.utf8String()).isEqualTo("hello");
    }

    @Test
    void utf8StringShouldHandleNonAscii() {
        OutputFrame frame =
                new OutputFrame(OutputFrame.Type.STDOUT, "\u00e9\u00e8\u00ea".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.utf8String()).isEqualTo("\u00e9\u00e8\u00ea");
    }

    @Test
    void utf8StringWithoutLineEndingShouldStripNewline() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "hello\n".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.utf8StringWithoutLineEnding()).isEqualTo("hello");
    }

    @Test
    void utf8StringWithoutLineEndingShouldStripCrlf() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "hello\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.utf8StringWithoutLineEnding()).isEqualTo("hello");
    }

    @Test
    void utf8StringWithoutLineEndingShouldPreserveNoEnding() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.utf8StringWithoutLineEnding()).isEqualTo("hello");
    }

    @Test
    void utf8StringWithoutLineEndingShouldHandleEmptyPayload() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[0]);
        assertThat(frame.utf8StringWithoutLineEnding()).isEqualTo("");
    }

    @Test
    void stringWithCharsetShouldDecodeContent() {
        // ISO-8859-1 encoding of \u00e9 is 0xe9
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {(byte) 0xe9});
        assertThat(frame.string(StandardCharsets.ISO_8859_1)).isEqualTo("\u00e9");
    }

    @Test
    void stringWithNullCharsetShouldThrow() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[0]);
        assertThatThrownBy(() -> frame.string(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("charset must not be null");
    }

    @Test
    void emptyBytesShouldReturnEmptyString() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[0]);
        assertThat(frame.utf8String()).isEqualTo("");
    }

    @Test
    void typeMustNotBeNull() {
        assertThatThrownBy(() -> new OutputFrame(null, new byte[0]))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("type must not be null");
    }

    @Test
    void bytesMustNotBeNull() {
        assertThatThrownBy(() -> new OutputFrame(OutputFrame.Type.STDOUT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bytes must not be null");
    }

    @Test
    void safeUtf8StringShouldPassOrdinaryAsciiUnchanged() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("hello");
    }

    @Test
    void safeUtf8StringShouldPreservePrintableUnicode() {
        OutputFrame frame = new OutputFrame(
                OutputFrame.Type.STDOUT, "caf\u00e9 r\u00e9sum\u00e9 \uD83C\uDF89".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("caf\u00e9 r\u00e9sum\u00e9 \uD83C\uDF89");
    }

    @Test
    void safeUtf8StringShouldRemoveNul() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x00, 0x62, 0x00, 0x63});
        assertThat(frame.safeUtf8String()).isEqualTo("abc");
    }

    @Test
    void safeUtf8StringShouldRemoveBel() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x07, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveBackspace() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x08, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveVerticalTab() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x0B, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveFormFeed() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x0C, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveLoneEsc() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x1B, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldPreserveTabLfCr() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "a\tb\nc\rd".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("a\tb\nc\rd");
    }

    @Test
    void safeUtf8StringShouldRemoveDel() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x7F, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveC1Controls() {
        OutputFrame frame = new OutputFrame(
                OutputFrame.Type.STDOUT,
                new byte[] {0x61, (byte) 0xC2, (byte) 0x80, (byte) 0xC2, (byte) 0x90, (byte) 0xC2, (byte) 0x9F, 0x62});
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveSgrCsiSequence() {
        OutputFrame frame =
                new OutputFrame(OutputFrame.Type.STDOUT, "hello\033[31mred\033[0m".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("hellored");
    }

    @Test
    void safeUtf8StringShouldRemoveCursorCsiSequence() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "a\033[2Jb".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("ab");
    }

    @Test
    void safeUtf8StringShouldRemoveOscTitleSequence() {
        OutputFrame frame =
                new OutputFrame(OutputFrame.Type.STDOUT, "\033]0;title\007text".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("text");
    }

    @Test
    void safeUtf8StringShouldRemoveOscStSequence() {
        OutputFrame frame =
                new OutputFrame(OutputFrame.Type.STDOUT, "\033]0;title\033\\text".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8String()).isEqualTo("text");
    }

    @Test
    void safeUtf8StringShouldHandleMalformedUtf8() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {(byte) 0xC3, 0x28});
        assertThatCode(() -> frame.safeUtf8String()).doesNotThrowAnyException();
        String result = frame.safeUtf8String();
        assertThat(result).contains("\uFFFD").contains("(");
    }

    @Test
    void safeUtf8StringShouldReturnEmptyForAllControls() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x00, 0x07, 0x1B, 0x7F});
        assertThat(frame.safeUtf8String()).isEqualTo("");
    }

    @Test
    void safeUtf8StringWithoutLineEndingShouldStripLf() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "safe\n".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8StringWithoutLineEnding()).isEqualTo("safe");
    }

    @Test
    void safeUtf8StringWithoutLineEndingShouldStripCrlf() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "safe\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8StringWithoutLineEnding()).isEqualTo("safe");
    }

    @Test
    void safeUtf8StringWithoutLineEndingShouldPreserveLoneCr() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "safe\r".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8StringWithoutLineEnding()).isEqualTo("safe\r");
    }

    @Test
    void safeUtf8StringWithoutLineEndingShouldPreserveNoEnding() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, "safe".getBytes(StandardCharsets.UTF_8));
        assertThat(frame.safeUtf8StringWithoutLineEnding()).isEqualTo("safe");
    }

    @Test
    void rawMethodsShouldStillContainNul() {
        OutputFrame frame = new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x00, 0x62});
        assertThat(frame.utf8String()).contains("\0");
        assertThat(frame.bytes()[1]).isEqualTo((byte) 0x00);
    }
}
