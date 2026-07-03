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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LogStreamHandleTest {

    @Test
    void shouldRejectNullCallback() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LogStreamHandle(null))
                .withMessage("callback must not be null");
    }

    @Test
    void shouldCloseUnderlyingCallback() {
        CountingCloseable callback = new CountingCloseable();

        new LogStreamHandle(callback).close();

        assertThat(callback.closeCount.get()).isOne();
    }

    @Test
    void shouldCloseAtMostOnce() {
        CountingCloseable callback = new CountingCloseable();
        LogStreamHandle handle = new LogStreamHandle(callback);

        handle.close();
        handle.close();

        assertThat(callback.closeCount.get()).isOne();
    }

    @Test
    void shouldSwallowIOExceptionFromUnderlyingClose() {
        // The container is being torn down; a failing underlying close must not propagate.
        LogStreamHandle handle = new LogStreamHandle(new FailingCloseable());

        handle.close();
        // No exception propagated; idempotent re-close also stays silent.
        handle.close();
    }

    @Test
    void shouldCloseAtMostOnceUnderConcurrency() throws InterruptedException {
        CountingCloseable callback = new CountingCloseable();
        LogStreamHandle handle = new LogStreamHandle(callback);
        int threads = 16;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                handle.close();
                return null;
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(terminated).isTrue();
        assertThat(callback.closeCount.get()).isOne();
    }

    /** Minimal {@link Closeable} that counts how many times {@link #close()} is invoked. */
    private static final class CountingCloseable implements Closeable {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    /** {@link Closeable} whose {@link #close()} always throws, to exercise error swallowing. */
    private static final class FailingCloseable implements Closeable {

        @Override
        public void close() throws IOException {
            throw new IOException("boom");
        }
    }

    @Test
    void shouldSwallowRuntimeExceptionFromUnderlyingClose() {
        Closeable throwingCloseable = () -> {
            throw new IllegalStateException("okhttp internal error");
        };
        LogStreamHandle handle = new LogStreamHandle(throwingCloseable);
        handle.close(); // must not propagate
        handle.close(); // idempotent re-close also silent
    }

    @Test
    void shouldDispatchBlankLineToRawConsumerButNotDisplayConsumer() throws Exception {
        Class<?> lineBufferClass = Class.forName("nonapi.org.altcontainers.LogOperations$LineBuffer");
        Object buffer = lineBufferClass.getDeclaredConstructor().newInstance();

        java.lang.reflect.Method append = lineBufferClass.getDeclaredMethod("append", byte[].class);
        append.setAccessible(true);
        append.invoke(buffer, (Object) "\n".getBytes());

        List<String> displayLines = new ArrayList<>();
        List<String> rawLines = new ArrayList<>();

        java.lang.reflect.Method drainLines = lineBufferClass.getDeclaredMethod(
                "drainLines", java.util.function.Consumer.class, java.util.function.Consumer.class);
        drainLines.setAccessible(true);
        drainLines.invoke(
                buffer, (java.util.function.Consumer<String>) displayLines::add, (java.util.function.Consumer<String>)
                        rawLines::add);

        assertThat(rawLines).contains("\n");
        assertThat(displayLines).isEmpty();
    }

    @Test
    void shouldDiscardBufferWhenExceedingMaxSize() throws Exception {
        Class<?> lineBufferClass = Class.forName("nonapi.org.altcontainers.LogOperations$LineBuffer");
        Object buffer = lineBufferClass.getDeclaredConstructor().newInstance();

        java.lang.reflect.Method append = lineBufferClass.getDeclaredMethod("append", byte[].class);
        append.setAccessible(true);

        // Append 10 MB + 1 byte of data without newline — this exceeds MAX_BUFFER_SIZE
        // and should cause the buffer to reset.
        byte[] largePayload = new byte[10 * 1024 * 1024 + 1];
        append.invoke(buffer, (Object) largePayload);

        // Next append should work normally on a fresh buffer.
        append.invoke(buffer, (Object) "hello\n".getBytes());

        List<String> rawLines = new ArrayList<>();
        java.lang.reflect.Method drainLines = lineBufferClass.getDeclaredMethod(
                "drainLines", java.util.function.Consumer.class, java.util.function.Consumer.class);
        drainLines.setAccessible(true);
        drainLines.invoke(
                buffer, (java.util.function.Consumer<String>) line -> {}, (java.util.function.Consumer<String>)
                        rawLines::add);

        assertThat(rawLines).contains("hello\n");
    }

    @Test
    void shouldAcceptDataAtExactMaxBufferSize() throws Exception {
        Class<?> lineBufferClass = Class.forName("nonapi.org.altcontainers.LogOperations$LineBuffer");
        Object buffer = lineBufferClass.getDeclaredConstructor().newInstance();

        java.lang.reflect.Method append = lineBufferClass.getDeclaredMethod("append", byte[].class);
        append.setAccessible(true);

        // Append just under 10 MB, then one byte, then a newline.
        // Total is exactly 10 MB which is within the limit.
        byte[] almostFull = new byte[10 * 1024 * 1024 - 2];
        append.invoke(buffer, (Object) almostFull);
        append.invoke(buffer, (Object) "x".getBytes());
        append.invoke(buffer, (Object) "\n".getBytes());

        List<String> rawLines = new ArrayList<>();
        java.lang.reflect.Method drainLines = lineBufferClass.getDeclaredMethod(
                "drainLines", java.util.function.Consumer.class, java.util.function.Consumer.class);
        drainLines.setAccessible(true);
        drainLines.invoke(
                buffer, (java.util.function.Consumer<String>) line -> {}, (java.util.function.Consumer<String>)
                        rawLines::add);

        assertThat(rawLines).isNotEmpty();
        assertThat(rawLines.get(0)).endsWith("x\n");
    }

    @Test
    void shouldDispatchRawLineWhenDisplayConsumerThrows() throws Exception {
        // Access private inner class LineBuffer via reflection
        Class<?> lineBufferClass = Class.forName("nonapi.org.altcontainers.LogOperations$LineBuffer");
        Object buffer = lineBufferClass.getDeclaredConstructor().newInstance();

        // Append "hello\n" bytes
        java.lang.reflect.Method append = lineBufferClass.getDeclaredMethod("append", byte[].class);
        append.setAccessible(true);
        append.invoke(buffer, (Object) "hello\n".getBytes());

        // Raw consumer records lines
        List<String> rawLines = new ArrayList<>();
        java.util.function.Consumer<String> display = line -> {
            throw new RuntimeException("display fail");
        };
        java.util.function.Consumer<String> raw = rawLines::add;

        // Call drainLines — should NOT throw despite display consumer exception
        java.lang.reflect.Method drainLines = lineBufferClass.getDeclaredMethod(
                "drainLines", java.util.function.Consumer.class, java.util.function.Consumer.class);
        drainLines.setAccessible(true);
        drainLines.invoke(buffer, display, raw);

        // Raw consumer must have received the line
        assertThat(rawLines).contains("hello\n");
    }
}
