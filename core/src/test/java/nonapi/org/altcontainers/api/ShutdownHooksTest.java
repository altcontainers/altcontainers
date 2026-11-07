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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the stop-executor shutdown task resolves its executor when the task
 * runs.
 *
 * <p>The managers register the hook from a constructor that runs during static
 * initialization, before their {@code STOP_EXECUTOR} field is assigned. Resolving
 * the executor when the task runs keeps that registration safe; capturing it when
 * the task is created yields a {@code null} executor and an NPE on every JVM exit.
 */
class ShutdownHooksTest {

    @Test
    void shutdownTaskResolvesExecutorWhenRunNotWhenCreated() {
        AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
        AtomicBoolean marked = new AtomicBoolean(false);

        Runnable task = ShutdownHooks.stopExecutorShutdown(() -> marked.set(true), executorRef::get);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executorRef.set(executor);
        try {
            assertThatCode(task::run).doesNotThrowAnyException();
            assertThat(marked).isTrue();
            assertThat(executor.isShutdown()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void shutdownTaskForcesExecutorDownWhenInterrupted() {
        AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
        Runnable task = ShutdownHooks.stopExecutorShutdown(() -> {}, executorRef::get);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executorRef.set(executor);

        Thread.currentThread().interrupt();
        try {
            task.run();
            assertThat(executor.isShutdown()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
    }
}
