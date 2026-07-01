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

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link Consumer} that formats and prints Docker container log lines to {@link System#out}.
 *
 * <p>Each non-blank line is output as {@code [prefix] image | line} followed by the platform line
 * separator. Null or blank lines are silently ignored. Instances are immutable and safe to share between
 * threads.
 *
 * <p>Created via {@link #of(String, String)} rather than direct construction.
 */
public final class PrefixConsumer implements Consumer<String> {

    private final String prefix;
    private final String image;

    /**
     * Private constructor; use {@link #of(String, String)}.
     *
     * @param prefix the label prepended in brackets for identification
     * @param image the Docker image name for identification
     * @throws NullPointerException if either argument is {@code null}
     */
    private PrefixConsumer(String prefix, String image) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.image = Objects.requireNonNull(image, "image");
    }

    /**
     * Creates a prefixed log consumer.
     *
     * @param prefix the label prepended in brackets for identification
     *     (for example {@code "JMX_EXPORTER_JAVAAGENT"}); must not be {@code null}
     * @param image the Docker image name for identification; must not be {@code null}
     * @return a new {@code PrefixConsumer}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static PrefixConsumer of(String prefix, String image) {
        return new PrefixConsumer(prefix, image);
    }

    /**
     * Prints a formatted line to {@link System#out} as {@code [prefix] image | line}. The input is
     * expected to be a single newline-stripped line. Null or blank input is silently ignored.
     *
     * @param line the log line; may be {@code null} or blank
     */
    @Override
    public void accept(String line) {
        if (line != null && !line.isBlank()) {
            System.out.println("[" + prefix + "] " + image + " | " + line);
        }
    }
}
