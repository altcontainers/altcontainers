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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.StartupContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies lifecycle hook firing semantics: {@code onStart},
 * {@code onStartFailure}, {@code onReady}, and {@code onClose}.
 *
 * <p>These tests require a running Docker daemon.
 */
@Tag("docker")
class LifecycleHooksTest {

    /**
     * A log message that will never appear from {@code sh -c "sleep N"},
     * used to force readiness timeouts.
     */
    private static final String NEVER_MATCHED = ".*THIS MESSAGE WILL NEVER BE LOGGED BY SLEEP.*";

    /**
     * A spec for a long-running container whose readiness will always fail
     * because the log message never appears.
     */
    private static final ContainerSpec READINESS_FAILURE_BASE = ContainerSpec.builder("alpine:latest")
            .command("sh", "-c", "sleep 30")
            .waitForLogMessage(NEVER_MATCHED, 1)
            .startupTimeout(Duration.ofSeconds(1))
            .build();

    // ──────────────────────────────────────────────
    // onStart
    // ──────────────────────────────────────────────

    @Test
    void onStartReceivesValidHostPort() throws Exception {
        List<Integer> observedPorts = new ArrayList<>();
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onStart(ctx -> observedPorts.add(ctx.container().hostPort(8080)))
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        try (Container container = Container.create(spec)) {
            assertThat(observedPorts).hasSize(1);
            assertThat(observedPorts.get(0)).isNotNull();
        }
    }

    @Test
    void onStartFiresPerEligibleAttempt() {
        List<Integer> attempts = new ArrayList<>();
        ContainerSpec spec = READINESS_FAILURE_BASE.with(b -> b.startupAttempts(3)
                .onStart(ctx -> attempts.add(ctx.attempt()))
                .onStartFailure(ctx -> {
                    /* ignore */
                }));

        assertThatThrownBy(() -> {
                    try (Container ignored = Container.create(spec)) {}
                })
                .isInstanceOf(ContainerException.class);

        assertThat(attempts).containsExactly(1, 2, 3);
    }

    @Test
    void multipleCallbacksPerPhaseFireInOrder() throws Exception {
        List<Integer> order = new ArrayList<>();
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onStart(ctx -> order.add(0))
                .onStart(ctx -> order.add(1))
                .onStart(ctx -> order.add(2))
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        try (Container container = Container.create(spec)) {
            assertThat(order).containsExactly(0, 1, 2);
        }
    }

    // ──────────────────────────────────────────────
    // onStartFailure
    // ──────────────────────────────────────────────

    @Test
    void onStartFailureFiresBeforeDoomedCleanup() {
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();

        ContainerSpec spec = READINESS_FAILURE_BASE.with(b -> b.startupAttempts(2)
                .onStartFailure(ctx -> failureCount.incrementAndGet())
                .onClose(c -> closeCount.incrementAndGet()));

        assertThatThrownBy(() -> {
                    try (Container ignored = Container.create(spec)) {}
                })
                .isInstanceOf(ContainerException.class);

        assertThat(failureCount.get()).isEqualTo(2);
        assertThat(closeCount.get()).isEqualTo(0);
    }

    @Test
    void onStartFailureContainsOriginalFailure() {
        AtomicReference<Throwable> capturedFailure = new AtomicReference<>();

        ContainerSpec spec = READINESS_FAILURE_BASE.with(
                b -> b.startupAttempts(1).onStartFailure(ctx -> capturedFailure.set(ctx.failure())));

        assertThatThrownBy(() -> {
                    try (Container ignored = Container.create(spec)) {}
                })
                .isInstanceOf(ContainerException.class);

        assertThat(capturedFailure.get()).isNotNull();
        assertThat(capturedFailure.get()).isInstanceOf(ContainerException.class);
    }

    @Test
    void onStartFailureThrowingCallbackAbortsStartup() {
        RuntimeException callbackError = new RuntimeException("cb");

        ContainerSpec spec =
                READINESS_FAILURE_BASE.with(b -> b.startupAttempts(1).onStartFailure(ctx -> {
                    throw callbackError;
                }));

        assertThatThrownBy(() -> {
                    try (Container ignored = Container.create(spec)) {}
                })
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("onStartFailure callback failed");
    }

    @Test
    void onStartFailureFiresForStartFailure() throws Exception {
        AtomicInteger startFailureCount = new AtomicInteger();
        int hostPort = findFreeHostPort();
        Map<Integer, Integer> bindings = new java.util.HashMap<>();
        bindings.put(8080, hostPort);

        ContainerSpec holderSpec = ContainerSpec.builder("alpine:latest")
                .command("sleep", "300")
                .exposePorts(8080)
                .portBindings(bindings)
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        try (Container holder = Container.create(holderSpec)) {
            ContainerSpec colliding = ContainerSpec.builder("alpine:latest")
                    .command("sleep", "300")
                    .exposePorts(8080)
                    .portBindings(bindings)
                    .startupAttempts(1)
                    .startupTimeout(Duration.ofSeconds(30))
                    .onStartFailure(ctx -> startFailureCount.incrementAndGet())
                    .build();

            assertThatThrownBy(() -> Container.create(colliding)).isInstanceOf(ContainerException.class);

            assertThat(startFailureCount.get()).isEqualTo(1);
        }
    }

    @Test
    void onStartFailureCallbackThrowAbortsOnStartFailure() throws Exception {
        AtomicInteger startFailureCount = new AtomicInteger();
        int hostPort = findFreeHostPort();
        Map<Integer, Integer> bindings = new java.util.HashMap<>();
        bindings.put(8080, hostPort);

        ContainerSpec holderSpec = ContainerSpec.builder("alpine:latest")
                .command("sleep", "300")
                .exposePorts(8080)
                .portBindings(bindings)
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        try (Container holder = Container.create(holderSpec)) {
            ContainerSpec colliding = ContainerSpec.builder("alpine:latest")
                    .command("sleep", "300")
                    .exposePorts(8080)
                    .portBindings(bindings)
                    .startupAttempts(2)
                    .startupTimeout(Duration.ofSeconds(30))
                    .onStartFailure(ctx -> {
                        startFailureCount.incrementAndGet();
                        throw new RuntimeException("callback boom");
                    })
                    .build();

            assertThatThrownBy(() -> Container.create(colliding))
                    .isInstanceOf(ContainerException.class)
                    .hasMessageContaining("onStartFailure callback failed");

            // Aborts on callback throw: no retry despite startupAttempts(2).
            assertThat(startFailureCount.get()).isEqualTo(1);
        }
    }

    private static int findFreeHostPort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ──────────────────────────────────────────────
    // onReady
    // ──────────────────────────────────────────────

    @Test
    void onReadyFiresOnceOnWinningAttempt() throws Exception {
        AtomicInteger readyCount = new AtomicInteger();
        AtomicReference<StartupContext> capturedCtx = new AtomicReference<>();

        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onReady(ctx -> {
                    readyCount.incrementAndGet();
                    capturedCtx.set(ctx);
                })
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        try (Container container = Container.create(spec)) {
            assertThat(readyCount.get()).isEqualTo(1);
            assertThat(capturedCtx.get().attempt()).isEqualTo(1);
            assertThat(capturedCtx.get().maxAttempts()).isEqualTo(1);
        }
    }

    @Test
    void onReadyDoesNotFireWhenAllAttemptsFail() {
        AtomicInteger readyCount = new AtomicInteger();

        ContainerSpec spec = READINESS_FAILURE_BASE.with(b -> b.startupAttempts(2)
                .onReady(ctx -> readyCount.incrementAndGet())
                .onStartFailure(ctx -> {
                    /* ignore */
                }));

        assertThatThrownBy(() -> {
                    try (Container ignored = Container.create(spec)) {}
                })
                .isInstanceOf(ContainerException.class);

        assertThat(readyCount.get()).isEqualTo(0);
    }

    // ──────────────────────────────────────────────
    // onClose
    // ──────────────────────────────────────────────

    @Test
    void onCloseFiresBeforeStopRemove() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();

        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onClose(c -> closeCount.incrementAndGet())
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        Container container = Container.create(spec);
        assertThat(closeCount.get()).isEqualTo(0);
        container.close();
        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    void onCloseFiresAtMostOnce() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();

        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onClose(c -> closeCount.incrementAndGet())
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        Container container = Container.create(spec);
        container.close();
        container.close();
        container.close();
        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    void onCloseExceptionStillCleansUp() throws Exception {
        RuntimeException callbackError = new RuntimeException("cb");

        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onClose(c -> {
                    throw callbackError;
                })
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        Container container = Container.create(spec);
        assertThatThrownBy(container::close).isInstanceOf(ContainerException.class);
    }

    @Test
    void multipleOnCloseFailuresCollected() throws Exception {
        RuntimeException error1 = new RuntimeException("error1");
        RuntimeException error2 = new RuntimeException("error2");

        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", "apk add --no-cache netcat-openbsd && nc -lk -p 8080 -e true")
                .exposePorts(8080)
                .onClose(c -> {
                    throw error1;
                })
                .onClose(c -> {
                    throw error2;
                })
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        Container container = Container.create(spec);
        Throwable t = catchThrowable(container::close);
        assertThat(t).isInstanceOf(ContainerException.class);
        assertThat(t.getSuppressed()).hasSize(1);
    }
}
