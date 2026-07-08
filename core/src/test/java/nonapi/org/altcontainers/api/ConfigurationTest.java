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

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.altcontainers.api.Altcontainers;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for client-side Configuration: {@link Configuration#load()} delegation
 * and compact-constructor validation. File/env precedence and eager validation
 * are covered by {@link AltcontainersPropertiesTest}.
 */
class ConfigurationTest {

    @BeforeEach
    void setUp() {
        Altcontainers.configure(null);
    }

    @AfterEach
    void tearDown() {
        Altcontainers.configure(null);
    }

    @Test
    void shouldUseDefaultsWhenNoSourceSet() {
        AltcontainersProperties properties =
                AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
        assertThat(properties.reaperDisabled()).isFalse();
        assertThat(properties.reaperConnectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.reaperStartupTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.reaperStopTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void shouldUseProgrammaticConfigOverFiles() {
        Altcontainers.configure(c -> c.reaperDisabled(true)
                .reaperConnectionTimeout(Duration.ofMillis(20000))
                .reaperStartupTimeout(Duration.ofSeconds(5))
                .reaperStopTimeout(Duration.ofSeconds(10)));
        try {
            Configuration config = Configuration.load();
            assertThat(config.disabled()).isTrue();
            assertThat(config.reaperConnectionTimeout()).isEqualTo(Duration.ofMillis(20000));
            assertThat(config.reaperStartupTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config.reaperStopTimeout()).isEqualTo(Duration.ofSeconds(10));
        } finally {
            Altcontainers.configure(null);
        }
    }

    @Test
    void shouldRejectNonPositiveConnectionTimeoutInProperties() {
        Properties userHome = new Properties();
        userHome.setProperty(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "0");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("connection.timeout.ms");
    }

    @Test
    void shouldRejectNonPositiveStartupTimeoutInProperties() {
        Properties userHome = new Properties();
        userHome.setProperty(AltcontainersProperties.REAPER_STARTUP_TIMEOUT_MS, "0");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("startup.timeout.ms");
    }

    @Test
    void shouldRejectNonPositiveStopTimeoutInProperties() {
        Properties userHome = new Properties();
        userHome.setProperty(AltcontainersProperties.REAPER_STOP_TIMEOUT_MS, "-5");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("stop.timeout.ms");
    }

    @Test
    void shouldRejectNonNumericConfigValue() {
        Properties userHome = new Properties();
        userHome.setProperty(AltcontainersProperties.REAPER_STOP_TIMEOUT_MS, "abc");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.REAPER_STOP_TIMEOUT_MS);
    }

    @Test
    void shouldRejectNonPositiveDurationsInRecord() {
        assertThatThrownBy(() -> new Configuration(false, Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(5)))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("connection.timeout.ms");
        assertThatThrownBy(() ->
                        new Configuration(false, Duration.ofSeconds(10), Duration.ofMillis(-1), Duration.ofSeconds(5)))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("startup.timeout.ms");
        assertThatThrownBy(
                        () -> new Configuration(false, Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ZERO))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("stop.timeout.ms");
    }
}
