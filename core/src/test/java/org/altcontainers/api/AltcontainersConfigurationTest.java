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
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AltcontainersConfiguration}.
 */
class AltcontainersConfigurationTest {

    @Test
    void shouldBuildWithDefaults() {
        AltcontainersConfiguration config = AltcontainersConfiguration.builder().build();
        assertThat(config.reaperDisabled()).isFalse();
        assertThat(config.reaperConnectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.reaperStartupTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.reaperStopTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.containerStartupTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.containerReadinessPollInitial()).isEqualTo(Duration.ofMillis(10));
        assertThat(config.containerReadinessPollMax()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.containerStartupRetryBackoffMultiplier()).isEqualTo(Duration.ofMillis(1000));
        assertThat(config.containerStartupRetryBackoffMax()).isEqualTo(Duration.ofMillis(5000));
        assertThat(config.portProbeTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.httpProbeTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(config.containerPutArchivePipeBufferBytes()).isEqualTo(65536);
    }

    @Test
    void shouldBuildWithCustomValues() {
        AltcontainersConfiguration config = AltcontainersConfiguration.builder()
                .reaperDisabled(true)
                .reaperConnectionTimeout(Duration.ofSeconds(30))
                .reaperStartupTimeout(Duration.ofSeconds(20))
                .reaperStopTimeout(Duration.ofSeconds(15))
                .containerStartupTimeout(Duration.ofSeconds(120))
                .containerReadinessPollInitial(Duration.ofMillis(50))
                .containerReadinessPollMax(Duration.ofMillis(1000))
                .containerStartupRetryBackoffMultiplier(Duration.ofMillis(2000))
                .containerStartupRetryBackoffMax(Duration.ofSeconds(10))
                .portProbeTimeout(Duration.ofMillis(750))
                .httpProbeTimeout(Duration.ofSeconds(3))
                .containerPutArchivePipeBufferBytes(131072)
                .build();
        assertThat(config.reaperDisabled()).isTrue();
        assertThat(config.reaperConnectionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.reaperStartupTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(config.reaperStopTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.containerStartupTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.containerReadinessPollInitial()).isEqualTo(Duration.ofMillis(50));
        assertThat(config.containerReadinessPollMax()).isEqualTo(Duration.ofMillis(1000));
        assertThat(config.containerStartupRetryBackoffMultiplier()).isEqualTo(Duration.ofMillis(2000));
        assertThat(config.containerStartupRetryBackoffMax()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.portProbeTimeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(config.httpProbeTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.containerPutArchivePipeBufferBytes()).isEqualTo(131072);
    }

    @Test
    void shouldRejectNullDurations() {
        assertThatThrownBy(() -> new AltcontainersConfiguration(
                        false,
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60),
                        Duration.ofMillis(10),
                        Duration.ofMillis(500),
                        Duration.ofMillis(1000),
                        Duration.ofMillis(5000),
                        Duration.ofMillis(500),
                        Duration.ofMillis(2000),
                        65536))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reaperConnectionTimeout");
    }

    @Test
    void shouldRejectNonPositiveDurations() {
        assertThatThrownBy(() -> AltcontainersConfiguration.builder()
                        .reaperConnectionTimeout(Duration.ZERO)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reaperConnectionTimeout");
        assertThatThrownBy(() -> AltcontainersConfiguration.builder()
                        .containerStartupTimeout(Duration.ofMillis(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("containerStartupTimeout");
        assertThatThrownBy(() -> AltcontainersConfiguration.builder()
                        .httpProbeTimeout(Duration.ZERO)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("httpProbeTimeout");
        assertThatThrownBy(() -> AltcontainersConfiguration.builder()
                        .containerPutArchivePipeBufferBytes(0)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("containerPutArchivePipeBufferBytes");
        assertThatThrownBy(() -> AltcontainersConfiguration.builder()
                        .containerPutArchivePipeBufferBytes(-1)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("containerPutArchivePipeBufferBytes");
    }
}
