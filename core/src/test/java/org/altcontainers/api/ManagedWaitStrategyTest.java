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

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import nonapi.org.altcontainers.api.ConcreteContainer;
import nonapi.org.altcontainers.api.ContainerMetadata;
import nonapi.org.altcontainers.api.ManagedWaitStrategy;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ManagedWaitStrategy} behavior matches the old
 * {@link WaitStrategy} defaults and that built-in strategies implement
 * the managed contract while bare lambdas do not.
 */
class ManagedWaitStrategyTest {

    private static Container dummyContainer(String id) {
        return new ConcreteContainer(
                id,
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("localhost", true, Map.of()));
    }

    @Test
    void shouldBeFunctionalInterface() {
        WaitStrategy ws = container -> true;
        assertThat(ws.check(dummyContainer("test1"))).isTrue();
    }

    @Test
    void builtInPortStrategyIsManaged() {
        WaitStrategy ws = Wait.forListeningPort(8080);
        assertThat(ws).isInstanceOf(ManagedWaitStrategy.class);
    }

    @Test
    void builtInLogStrategyIsManaged() {
        WaitStrategy ws = Wait.forLogMessage(".*ready.*", 1);
        assertThat(ws).isInstanceOf(ManagedWaitStrategy.class);
    }

    @Test
    void builtInHttpStrategyIsManaged() {
        WaitStrategy ws = Wait.forHttpResponse(HttpWaitStrategy.Protocol.HTTP, 8080, "/", 200, 399);
        assertThat(ws).isInstanceOf(ManagedWaitStrategy.class);
    }

    @Test
    void lambdaStrategyIsNotManaged() {
        WaitStrategy ws = container -> true;
        assertThat(ws).isNotInstanceOf(ManagedWaitStrategy.class);
    }

    @Test
    void statelessPortStrategyReturnsSelfFromNewAttempt() {
        ManagedWaitStrategy s = (ManagedWaitStrategy) Wait.forListeningPort(8080);
        assertThat(s.newAttemptCondition()).isSameAs(s);
    }

    @Test
    void statelessHttpStrategyReturnsSelfFromNewAttempt() {
        ManagedWaitStrategy s =
                (ManagedWaitStrategy) Wait.forHttpResponse(HttpWaitStrategy.Protocol.HTTP, 8080, "/", 200, 399);
        assertThat(s.newAttemptCondition()).isSameAs(s);
    }

    @Test
    void logStrategyReturnsFreshCounterFromNewAttempt() {
        LogWaitStrategy original = (LogWaitStrategy) Wait.forLogMessage(".*x.*", 3);

        // Pre-feed raw log matches to the original (3 matches to satisfy)
        Consumer<String> rawConsumer = original.logLineConsumer();
        rawConsumer.accept("a x b\n");
        rawConsumer.accept("x\n");
        rawConsumer.accept("pre x post\n");

        // Original is satisfied
        assertThat(original.check(dummyContainer("test2"))).isTrue();

        // Fresh copy has reset counter
        ManagedWaitStrategy fresh = original.newAttemptCondition();
        assertThat(fresh).isNotSameAs(original);
        assertThat(fresh.check(dummyContainer("test3"))).isFalse();
    }

    @Test
    void managedDefaultLogLineConsumerReturnsNull() {
        ManagedWaitStrategy s = (ManagedWaitStrategy) Wait.forListeningPort(8080);
        assertThat(s.logLineConsumer()).isNull();
    }

    @Test
    void managedDefaultTimeoutDiagnosticReturnsSimpleClassName() {
        // Use a helper strategy that inherits the default timeoutDiagnostic.
        ManagedWaitStrategy s = new DefaultDiagnosticStrategy();
        Container c = dummyContainer("test4");
        assertThat(s.timeoutDiagnostic(c, Duration.ofSeconds(30))).isEqualTo("DefaultDiagnosticStrategy");
    }

    /**
     * Minimal ManagedWaitStrategy that inherits default timeoutDiagnostic.
     */
    private static final class DefaultDiagnosticStrategy implements ManagedWaitStrategy {
        @Override
        public boolean check(Container container) {
            return true;
        }
    }

    @Test
    void mixedCompositeResetsOnlyManagedChildren() {
        WaitStrategy lambda = container -> true;
        ManagedWaitStrategy composite = (ManagedWaitStrategy) Wait.allOf(Wait.forLogMessage(".*x.*", 1), lambda);

        // Access managed children via reflection or just test behavior:
        // The fresh copy should be a new composite (not same object)
        ManagedWaitStrategy fresh = composite.newAttemptCondition();
        assertThat(fresh).isNotSameAs(composite);
    }

    @Test
    void mixedCompositeRawLogFansOutToManagedOnly() {
        // Use a log strategy that overrides logLineConsumer
        LogWaitStrategy log = (LogWaitStrategy) Wait.forLogMessage(".*x.*", 1);
        WaitStrategy lambda = container -> true;
        ManagedWaitStrategy composite = (ManagedWaitStrategy) Wait.allOf(log, lambda);

        Consumer<String> fanOut = composite.logLineConsumer();
        assertThat(fanOut).isNotNull();

        // Feed a matching line; it should reach the log child
        fanOut.accept("xxx\n");
        assertThat(log.check(dummyContainer("test5"))).isTrue();
    }

    @Test
    void compositeTimeoutDiagnosticUsesChildren() {
        ManagedWaitStrategy composite =
                (ManagedWaitStrategy) Wait.allOf(Wait.forListeningPort(8080), Wait.forLogMessage(".*x.*", 1));
        Container c = dummyContainer("test6");
        String diagnostic = composite.timeoutDiagnostic(c, Duration.ofSeconds(30));
        assertThat(diagnostic).contains("PortWaitStrategy");
        assertThat(diagnostic).contains("LogWaitStrategy");
    }
}
