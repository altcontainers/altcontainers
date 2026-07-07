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

import java.util.Map;
import nonapi.org.altcontainers.api.ConcreteContainer;
import nonapi.org.altcontainers.api.ContainerMetadata;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link StartupFailure} validation and accessor contracts.
 */
class StartupFailureTest {

    private static Container dummyContainer() {
        return new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("localhost", true, Map.of()));
    }

    @Test
    void shouldAcceptValidFailure() {
        Container c = dummyContainer();
        RuntimeException failure = new RuntimeException("fail");
        StartupFailure ctx = new StartupFailure(c, 1, 3, failure);
        assertThat(ctx.container()).isSameAs(c);
        assertThat(ctx.attempt()).isEqualTo(1);
        assertThat(ctx.maxAttempts()).isEqualTo(3);
        assertThat(ctx.failure()).isSameAs(failure);
    }

    @Test
    void shouldRejectNullContainer() {
        assertThatThrownBy(() -> new StartupFailure(null, 1, 3, new RuntimeException("fail")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("container");
    }

    @Test
    void shouldRejectNullFailure() {
        assertThatThrownBy(() -> new StartupFailure(dummyContainer(), 1, 3, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("failure");
    }

    @Test
    void shouldRejectAttemptZero() {
        assertThatThrownBy(() -> new StartupFailure(dummyContainer(), 0, 3, new RuntimeException("fail")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void shouldRejectAttemptAboveMax() {
        assertThatThrownBy(() -> new StartupFailure(dummyContainer(), 4, 3, new RuntimeException("fail")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }
}
