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
 *                 System.err.println(frame.utf8StringWithoutLineEnding());
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
}
