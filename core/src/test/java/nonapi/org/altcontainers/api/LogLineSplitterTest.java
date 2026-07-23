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

package nonapi.org.altcontainers.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.altcontainers.api.LogWaitStrategy;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ContainerManager.LineSplittingConsumer} line-buffering
 * semantics: multi-line frame splitting, cross-frame line reassembly, and
 * trailing partial line flush.
 */
class LogLineSplitterTest {

    private static ContainerManager.LineSplittingConsumer splitter(Consumer<String> downstream) {
        return ContainerManager.lineSplitting(downstream);
    }

    @Test
    void multiLineFrameCountsEachLine() {
        // LogWaitStrategy expects 2 "ready" lines
        LogWaitStrategy strategy = new LogWaitStrategy(".*ready.*", 2);
        Consumer<String> lineConsumer = strategy.logLineConsumer();
        ContainerManager.LineSplittingConsumer splitter = splitter(lineConsumer);

        // Single frame containing two matching lines
        splitter.accept("ready\nready\n");

        assertThat(strategy.check(null)).isTrue();
    }

    @Test
    void splitLineAcrossFrames() {
        LogWaitStrategy strategy = new LogWaitStrategy(".*ready.*", 1);
        Consumer<String> lineConsumer = strategy.logLineConsumer();
        ContainerManager.LineSplittingConsumer splitter = splitter(lineConsumer);

        // First frame: partial line (no newline)
        splitter.accept("rea");
        assertThat(strategy.check(null)).isFalse();

        // Second frame: rest of the line
        splitter.accept("dy\n");
        assertThat(strategy.check(null)).isTrue();
    }

    @Test
    void trailingPartialLineFlushedOnComplete() {
        LogWaitStrategy strategy = new LogWaitStrategy(".*ready.*", 1);
        Consumer<String> lineConsumer = strategy.logLineConsumer();
        ContainerManager.LineSplittingConsumer splitter = splitter(lineConsumer);

        // Frame with no trailing newline
        splitter.accept("ready");
        assertThat(strategy.check(null)).isFalse();

        // Flush on stream completion
        splitter.flush();
        assertThat(strategy.check(null)).isTrue();
    }

    @Test
    void newlineTerminatedLinesIncludeTrailingNewline() {
        List<String> captured = new ArrayList<>();
        ContainerManager.LineSplittingConsumer splitter = splitter(captured::add);

        splitter.accept("hello\n");

        assertThat(captured).containsExactly("hello\n");
    }

    @Test
    void emptyFrameDoesNotEmitSpuriousLine() {
        AtomicInteger count = new AtomicInteger(0);
        ContainerManager.LineSplittingConsumer splitter = splitter(line -> count.incrementAndGet());

        splitter.accept("");
        splitter.accept(null);

        assertThat(count.get()).isZero();
    }

    @Test
    void multipleNewlinesInOneFrame() {
        List<String> captured = new ArrayList<>();
        ContainerManager.LineSplittingConsumer splitter = splitter(captured::add);

        splitter.accept("a\nb\nc\n");

        assertThat(captured).containsExactly("a\n", "b\n", "c\n");
    }

    @Test
    void flushWithEmptyBufferIsNoop() {
        List<String> captured = new ArrayList<>();
        ContainerManager.LineSplittingConsumer splitter = splitter(captured::add);

        splitter.accept("line\n");
        splitter.flush(); // buffer is empty after full line

        assertThat(captured).containsExactly("line\n");
    }

    @Test
    void complexSplitAcrossMultipleFrames() {
        LogWaitStrategy strategy = new LogWaitStrategy(".*started.*", 2);
        Consumer<String> lineConsumer = strategy.logLineConsumer();
        ContainerManager.LineSplittingConsumer splitter = splitter(lineConsumer);

        // Frame 1: partial first line
        splitter.accept("server sta");
        assertThat(strategy.check(null)).isFalse();

        // Frame 2: rest of first line + newline + partial second line
        splitter.accept("rted\nclient sta");
        assertThat(strategy.check(null)).isFalse();

        // Frame 3: rest of second line
        splitter.accept("rted\n");
        assertThat(strategy.check(null)).isTrue();
    }
}
