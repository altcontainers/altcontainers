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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContainerSpec#toBuilder()} and {@link ContainerSpec#with(Consumer)}.
 */
class GenericContainerSpecTest {

    @BeforeEach
    void setUp() {
        Altcontainers.configure(null);
    }

    @AfterEach
    void tearDown() {
        Altcontainers.configure(null);
    }

    @Test
    void toBuilderShouldCopyAllFields() {
        ContainerSpec original = ContainerSpec.builder("alpine:latest")
                .exposePorts(8080, 9090)
                .command("sh", "-c", "echo hello")
                .bindDirectory("/host", "/container")
                .startupTimeout(Duration.ofMinutes(2))
                .startupAttempts(3)
                .environment(Map.of("KEY", "VALUE"))
                .memory(512 * 1024 * 1024L)
                .build();

        ContainerSpec rebuilt = original.toBuilder().build();

        assertThat(rebuilt.image()).isEqualTo("alpine:latest");
        assertThat(rebuilt.exposedPorts()).containsExactly(8080, 9090);
        assertThat(rebuilt.command()).containsExactly("sh", "-c", "echo hello");
        assertThat(rebuilt.bindMounts()).hasSize(1);
        assertThat(rebuilt.startupTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(rebuilt.startupAttempts()).isEqualTo(3);
        assertThat(rebuilt.environment()).containsEntry("KEY", "VALUE");
        assertThat(rebuilt.memory()).isEqualTo(512 * 1024 * 1024L);
    }

    @Test
    void toBuilderShouldNotBeSameInstance() {
        ContainerSpec original = ContainerSpec.builder("alpine:latest").build();
        ContainerSpec rebuilt = original.toBuilder().build();
        assertThat(rebuilt).isNotSameAs(original);
    }

    @Test
    void withShouldApplyCustomizations() {
        ContainerSpec original = ContainerSpec.builder("alpine:latest")
                .exposePorts(8080)
                .startupTimeout(Duration.ofSeconds(30))
                .build();

        ContainerSpec modified = original.with(b -> b.exposePorts(9090).startupTimeout(Duration.ofMinutes(1)));

        // Original unchanged
        assertThat(original.exposedPorts()).containsExactly(8080);
        assertThat(original.startupTimeout()).isEqualTo(Duration.ofSeconds(30));

        // Modified has additions
        assertThat(modified.exposedPorts()).containsExactly(8080, 9090);
        assertThat(modified.startupTimeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void shouldUseConfiguredDefaultStartupTimeout() {
        Altcontainers.configure(c -> c.containerStartupTimeout(Duration.ofSeconds(120)));
        ContainerSpec spec = ContainerSpec.builder("alpine:latest").build();
        assertThat(spec.startupTimeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void shouldLetExplicitStartupTimeoutOverrideConfigDefault() {
        Altcontainers.configure(c -> c.containerStartupTimeout(Duration.ofSeconds(120)));
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .startupTimeout(Duration.ofMinutes(2))
                .build();
        assertThat(spec.startupTimeout()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void waitStrategyShouldAddConditions() {
        WaitStrategy port = Wait.forListeningPort(8080);
        WaitStrategy log = Wait.forLogMessage(".*started.*", 1);
        ContainerSpec spec =
                ContainerSpec.builder("alpine:latest").waitStrategy(port, log).build();
        assertThat(spec.waitConditions()).containsExactly(port, log);
    }

    @Test
    void waitStrategyCombinedWithConvenienceMethods() {
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .waitForContainerPort(8080)
                .waitStrategy(Wait.anyOf(Wait.forListeningPort(9090), Wait.forLogMessage(".*ready.*", 1)))
                .build();
        assertThat(spec.waitConditions()).hasSize(2);
    }

    @Test
    void portBindingsAcceptsImmutableMap() {
        ContainerSpec spec = ContainerSpec.builder("test:latest")
                .exposePorts(8080)
                .portBindings(Map.of(8080, 18080))
                .build();

        assertThat(spec.portBindings()).containsEntry(8080, 18080);
    }

    @Test
    void portBindingsAcceptsMultiEntryImmutableMap() {
        ContainerSpec spec = ContainerSpec.builder("test:latest")
                .exposePorts(8080, 9090)
                .portBindings(Map.of(8080, 18080, 9090, 19090))
                .build();

        assertThat(spec.portBindings()).containsEntry(8080, 18080).containsEntry(9090, 19090);
    }

    @Test
    void portBindingsAcceptsEmptyImmutableMap() {
        ContainerSpec spec = ContainerSpec.builder("test:latest")
                .exposePorts(8080)
                .portBindings(Map.of())
                .build();

        assertThat(spec.portBindings()).isEmpty();
    }

    @Test
    void onOutputShouldStoreConsumersInRegistrationOrder() {
        AtomicInteger counter = new AtomicInteger(0);
        Consumer<OutputFrame> first = frame -> counter.incrementAndGet();
        Consumer<OutputFrame> second = frame -> counter.incrementAndGet();
        Consumer<OutputFrame> third = frame -> counter.incrementAndGet();

        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .onOutput(first)
                .onOutput(second)
                .onOutput(third)
                .build();

        assertThat(spec.onOutputConsumers()).containsExactly(first, second, third);
    }

    @Test
    void onOutputConsumersShouldBeCopiedThroughToBuilder() {
        Consumer<OutputFrame> consumer = frame -> {};

        ContainerSpec original =
                ContainerSpec.builder("alpine:latest").onOutput(consumer).build();

        ContainerSpec rebuilt = original.toBuilder().build();

        assertThat(rebuilt.onOutputConsumers()).containsExactly(consumer);
    }

    @Test
    void onOutputNullShouldThrow() {
        assertThatThrownBy(() -> ContainerSpec.builder("alpine:latest").onOutput(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("consumer must not be null");
    }
}
