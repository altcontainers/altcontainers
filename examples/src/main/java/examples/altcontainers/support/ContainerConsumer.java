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

package examples.altcontainers.support;

import java.util.Objects;
import java.util.function.Consumer;
import org.altcontainers.api.OutputFrame;

/**
 * A {@link Consumer} that formats and prints Docker container output frames to {@link System#out}.
 *
 * <p>Each non-blank frame is output as {@code [prefix] image | line} followed by the platform line
 * separator. Blank frames are silently ignored. Instances are immutable and safe to share between
 * threads.
 *
 * <p>Created via {@link #of(String, String)} rather than direct construction.
 */
public final class ContainerConsumer implements Consumer<OutputFrame> {

    private final String prefix;
    private final String image;

    /**
     * Private constructor; use {@link #of(String, String)}.
     *
     * @param prefix the label prepended in brackets for identification
     * @param image the Docker image name for identification
     * @throws NullPointerException if either argument is {@code null}
     */
    private ContainerConsumer(String prefix, String image) {
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
        this.image = Objects.requireNonNull(image, "image must not be null");
    }

    /**
     * Creates a prefixed output consumer.
     *
     * @param prefix the label prepended in brackets for identification
     *     (for example {@code "JMX_EXPORTER_JAVAAGENT"}); must not be {@code null}
     * @param image the Docker image name for identification; must not be {@code null}
     * @return a new {@code ContainerConsumer}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ContainerConsumer of(String prefix, String image) {
        return new ContainerConsumer(prefix, image);
    }

    /**
     * Prints a formatted line to {@link System#out} as {@code [prefix] image | line}. The frame is
     * decoded as UTF-8 with safe text conversion and stripped of trailing line endings. Blank output
     * is silently ignored.
     *
     * @param frame the output frame
     */
    @Override
    public void accept(OutputFrame frame) {
        String line = frame.safeUtf8StringWithoutLineEnding();
        if (!line.isBlank()) {
            System.out.println("[" + prefix + "] " + image + " | " + line);
        }
    }
}
