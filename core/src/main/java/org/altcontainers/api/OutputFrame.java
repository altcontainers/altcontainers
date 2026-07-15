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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A raw container output frame carrying bytes and Docker stream metadata.
 *
 * <p>{@code OutputFrame} represents a chunk of container stdout or stderr output. Frames are raw output
 * chunks with no line or message boundary guarantees. The framework does not strip, filter, decode, or
 * line-buffer frames before invoking output consumers.
 *
 * <p>Instances are immutable and safe to share between threads.
 *
 * <pre>{@code
 * ContainerSpec spec = ContainerSpec.builder("my-image:latest")
 *         .onOutput(frame -> {
 *             if (frame.type() == OutputFrame.Type.STDERR) {
 *                 System.err.println(frame.safeUtf8StringWithoutLineEnding());
 *             }
 *         })
 *         .build();
 * }</pre>
 */
public final class OutputFrame {

    /**
     * The stream type of a container output frame.
     */
    public enum Type {

        /** Standard output stream. */
        STDOUT,

        /** Standard error stream. */
        STDERR,

        /** Raw stream with no stream type distinction. */
        RAW,

        /** Unknown or unrecognized stream type. */
        UNKNOWN
    }

    private final Type type;
    private final byte[] bytes;

    /**
     * Creates a new output frame with the given type and bytes.
     *
     * <p>The byte array is defensively copied on construction and on access via {@link #bytes()}.
     *
     * @param type the stream type; must not be {@code null}
     * @param bytes the raw output bytes; must not be {@code null}
     * @throws NullPointerException if {@code type} or {@code bytes} is {@code null}
     */
    public OutputFrame(Type type, byte[] bytes) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.bytes = Objects.requireNonNull(bytes, "bytes must not be null").clone();
    }

    /**
     * Returns the stream type of this frame.
     *
     * @return the stream type, never {@code null}
     */
    public Type type() {
        return type;
    }

    /**
     * Returns a defensive copy of the raw output bytes.
     *
     * <p>Modifying the returned array does not affect this frame.
     *
     * @return a copy of the raw output bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Decodes the raw bytes using the given charset.
     *
     * @param charset the charset to use for decoding; must not be {@code null}
     * @return the decoded string
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public String string(Charset charset) {
        Objects.requireNonNull(charset, "charset must not be null");
        return new String(bytes, charset);
    }

    /**
     * Decodes the raw bytes as UTF-8.
     *
     * @return the UTF-8 decoded string
     */
    public String utf8String() {
        return string(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the raw bytes as UTF-8 and strips any trailing line ending.
     *
     * <p>Strips a trailing {@code \r\n} or {@code \n} if present. This is a convenience method for
     * text-oriented consumers; it does not imply that frames contain complete lines.
     *
     * @return the UTF-8 decoded string without trailing line ending
     */
    public String utf8StringWithoutLineEnding() {
        String text = utf8String();
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * Decodes the raw bytes as UTF-8 and returns a safe representation suitable for terminal display
     * and text log output.
     *
     * <p>The returned string has the following removed:
     *
     * <ul>
     *   <li>NUL ({@code U+0000})
     *   <li>Unsafe C0 controls ({@code U+0001}–{@code U+0008}, {@code U+000B}–{@code U+000C},
     *       {@code U+000E}–{@code U+001F})
     *   <li>DEL ({@code U+007F})
     *   <li>C1 controls ({@code U+0080}–{@code U+009F})
     *   <li>ANSI CSI sequences ({@code ESC [} … final byte {@code 0x40}–{@code 0x7E})
     *   <li>ANSI OSC sequences ({@code ESC ]} … {@code BEL} or {@code ESC \})
     * </ul>
     *
     * <p>The following are preserved:
     *
     * <ul>
     *   <li>Horizontal tab ({@code U+0009})
     *   <li>Line feed ({@code U+000A})
     *   <li>Carriage return ({@code U+000D})
     *   <li>All printable Unicode including diacritics, emoji, and supplementary characters
     * </ul>
     *
     * <p>Malformed UTF-8 is replaced with {@code U+FFFD} replacement characters per standard Java
     * decoding behavior. This method never throws and never returns {@code null}.
     *
     * <p>Use {@link #bytes()}, {@link #string(Charset)}, {@link #utf8String()}, or {@link
     * #utf8StringWithoutLineEnding()} when raw byte fidelity is required.
     *
     * @return the safe UTF-8 decoded string, never {@code null}
     * @since 0.2.0
     */
    public String safeUtf8String() {
        return sanitize(utf8String());
    }

    /**
     * Calls {@link #safeUtf8String()} and then strips any trailing line ending.
     *
     * <p>Strips a trailing {@code \r\n} or {@code \n} if present. This is a convenience method for
     * text-oriented consumers; it does not imply that frames contain complete lines.
     *
     * @return the safe UTF-8 decoded string without trailing line ending, never {@code null}
     * @since 0.2.0
     */
    public String safeUtf8StringWithoutLineEnding() {
        String text = safeUtf8String();
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int len = raw.length();
        int i = 0;
        while (i < len) {
            int cp = raw.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (cp == 0x1B) { // ESC
                i += charCount;
                if (i >= len) {
                    break;
                }
                int nextCp = raw.codePointAt(i);
                if (nextCp == 0x5B) { // CSI: ESC [
                    i += Character.charCount(nextCp);
                    while (i < len) {
                        int c = raw.codePointAt(i);
                        i += Character.charCount(c);
                        if (c >= 0x40 && c <= 0x7E) {
                            break; // final byte consumed, exit CSI
                        }
                    }
                } else if (nextCp == 0x5D) { // OSC: ESC ]
                    i += Character.charCount(nextCp);
                    while (i < len) {
                        int c = raw.codePointAt(i);
                        if (c == 0x07) { // BEL terminator
                            i += Character.charCount(c);
                            break;
                        }
                        if (c == 0x1B) { // possible ST: ESC \
                            i += Character.charCount(c);
                            if (i < len && raw.codePointAt(i) == 0x5C) {
                                i += Character.charCount(0x5C);
                                break; // ST terminator consumed
                            }
                            // Not ST, continue skipping
                            continue;
                        }
                        i += Character.charCount(c);
                    }
                }
                // lone ESC or unrecognized escape: ESC already consumed
            } else if (cp == 0x09 || cp == 0x0A || cp == 0x0D) { // TAB, LF, CR preserved
                sb.appendCodePoint(cp);
                i += charCount;
            } else if (cp >= 0x00 && cp <= 0x1F) { // unsafe C0 (TAB/LF/CR excluded above)
                i += charCount;
            } else if (cp == 0x7F) { // DEL
                i += charCount;
            } else if (cp >= 0x80 && cp <= 0x9F) { // C1 controls
                i += charCount;
            } else {
                sb.appendCodePoint(cp);
                i += charCount;
            }
        }
        return sb.toString();
    }
}
