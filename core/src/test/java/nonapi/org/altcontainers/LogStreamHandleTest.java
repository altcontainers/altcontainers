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
}
