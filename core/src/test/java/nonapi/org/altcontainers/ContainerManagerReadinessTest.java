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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.WaitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContainerManagerReadinessTest")
class ContainerManagerReadinessTest {

    @Test
    @DisplayName("awaitStrategies does not spin when remaining time is sub-millisecond")
    void shouldNotSpinWhenRemainingTimeSubMillisecond() {
        AtomicInteger checkCount = new AtomicInteger(0);
        WaitStrategy neverSatisfied = container -> {
            checkCount.incrementAndGet();
            return false;
        };

        Container container = new Container("test-id", "test-image");

        assertThatThrownBy(() ->
                        ContainerManager.awaitStrategies(container, Duration.ofMillis(2), List.of(neverSatisfied)))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("not ready");

        // With a 2 ms timeout, the loop should exit quickly after the first
        // remainingNanos < 1ms check — not spin through hundreds of iterations.
        assertThat(checkCount.get())
                .as("check() call count must be low, indicating no tight spin near deadline")
                .isLessThanOrEqualTo(5);
    }
}
