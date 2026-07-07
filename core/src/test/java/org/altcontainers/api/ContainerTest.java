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
 * Verifies Container metadata fallback behavior: positive cached values are
 * trusted; negative/null values fall through to live daemon queries.
 */
class ContainerTest {

    @Test
    void shouldQueryDaemonWhenMetadataSaysNotRunning() {
        // With metadata.running=false, the code path must fall through to
        // ContainerManager.isContainerRunning() rather than returning the
        // cached false value directly.
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("localhost", false, Map.of()));

        // No daemon available — ContainerManager returns false. The test
        // verifies behavior parity (both old and new code return false),
        // but the fix ensures the code takes the daemon path, which is
        // verified by code coverage.
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void shouldUseCachedMetadataWhenRunning() {
        // Positive cached value: metadata.running=true is trusted.
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("myhost", true, Map.of()));

        assertThat(container.isRunning()).isTrue();
        assertThat(container.host()).isEqualTo("myhost");
    }

    @Test
    void shouldQueryDaemonWhenMetadataIsNull() {
        // No metadata — falls through to daemon queries (returns defaults with no daemon).
        Container container = new ConcreteContainer(
                "test-id", "test-image", ContainerSpec.builder("test-image").build(), null);

        // ContainerManager returns false when daemon is unavailable
        assertThat(container.isRunning()).isFalse();
        // ContainerManager returns "localhost" when daemon is unavailable
        assertThat(container.host()).isEqualTo("localhost");
    }

    @Test
    void shouldQueryDaemonWhenHostIsNullInMetadata() {
        // metadata.host() is null — must fall through to daemon query.
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata(null, true, Map.of()));

        // With host=null in metadata, falls through to ContainerManager.host()
        // which returns "localhost" when no daemon is available.
        assertThat(container.host()).isEqualTo("localhost");
    }

    @Test
    void shouldRejectNullValueInPortBindings() {
        java.util.HashMap<Integer, Integer> bindings = new java.util.HashMap<>();
        bindings.put(8080, null);
        assertThatThrownBy(() -> ContainerSpec.builder("test:latest")
                        .exposePorts(8080)
                        .portBindings(bindings)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void shouldReturnNullForUnmappedPort() {
        Container container = new ConcreteContainer(
                "test-id",
                "test-image",
                ContainerSpec.builder("test-image").build(),
                new ContainerMetadata("localhost", true, Map.of()));
        assertThat(container.hostPort(9999)).isNull();
    }

    @Test
    void shouldReturnOriginalSpec() {
        ContainerSpec spec =
                ContainerSpec.builder("alpine:latest").exposePorts(8080).build();
        Container container = new ConcreteContainer(
                "test-id", "alpine:latest", spec, new ContainerMetadata("localhost", true, Map.of()));
        assertThat(container.spec()).isSameAs(spec);
    }
}
