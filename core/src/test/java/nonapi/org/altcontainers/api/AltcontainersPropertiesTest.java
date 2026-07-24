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
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AltcontainersProperties} resolution precedence and eager
 * validation. Uses the package-private {@link AltcontainersProperties#forTesting}
 * factory to avoid JVM-global state.
 */
class AltcontainersPropertiesTest {

    @BeforeEach
    void setUp() {
        clearSystemPropertyKeys();
        AltcontainersProperties.reset();
    }

    @AfterEach
    void tearDown() {
        clearSystemPropertyKeys();
        AltcontainersProperties.reset();
    }

    @Test
    void shouldApplyDefaultsWhenNoSourceSet() {
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
        assertThat(p.reaperDisabled()).isFalse();
        assertThat(p.reaperConnectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.reaperStartupTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.reaperStopTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.containerStartupTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.containerReadinessPollInitial()).isEqualTo(Duration.ofMillis(10));
        assertThat(p.containerReadinessPollMax()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.containerStartupRetryBackoffMultiplier()).isEqualTo(Duration.ofMillis(1000));
        assertThat(p.containerStartupRetryBackoffMax()).isEqualTo(Duration.ofMillis(5000));
        assertThat(p.portProbeTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.httpProbeTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(p.containerPutArchivePipeBufferBytes()).isEqualTo(65536);
    }

    @Test
    void shouldLetClasspathProvideValues() {
        Properties classpath = props(
                AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "7000",
                AltcontainersProperties.PORT_PROBE_TIMEOUT_MS, "333");
        AltcontainersProperties p = AltcontainersProperties.forTesting(classpath, new Properties(), Map.of());
        assertThat(p.reaperConnectionTimeout()).isEqualTo(Duration.ofMillis(7000));
        assertThat(p.portProbeTimeout()).isEqualTo(Duration.ofMillis(333));
    }

    @Test
    void shouldLetUserHomeOverrideClasspath() {
        Properties classpath = props(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "10000");
        Properties userHome = props(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "20000");
        AltcontainersProperties p = AltcontainersProperties.forTesting(classpath, userHome, Map.of());
        assertThat(p.reaperConnectionTimeout()).isEqualTo(Duration.ofMillis(20000));
    }

    @Test
    void shouldLetEnvOverrideFiles() {
        Properties classpath = props(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "10000");
        Properties userHome = props(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "20000");
        Map<String, String> env = Map.of("ALTCONTAINERS_REAPER_CONNECTION_TIMEOUT_MS", "30000");
        AltcontainersProperties p = AltcontainersProperties.forTesting(classpath, userHome, env);
        assertThat(p.reaperConnectionTimeout()).isEqualTo(Duration.ofMillis(30000));
    }

    @Test
    void shouldDeriveEnvVarNameAcrossNamespaces() {
        Map<String, String> env = Map.of(
                "ALTCONTAINERS_REAPER_STOP_TIMEOUT_MS", "4321",
                "ALTCONTAINERS_WAIT_PORT_PROBE_TIMEOUT_MS", "1234",
                "ALTCONTAINERS_CONTAINER_STARTUP_TIMEOUT_MS", "99000");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), env);
        assertThat(p.reaperStopTimeout()).isEqualTo(Duration.ofMillis(4321));
        assertThat(p.portProbeTimeout()).isEqualTo(Duration.ofMillis(1234));
        assertThat(p.containerStartupTimeout()).isEqualTo(Duration.ofMillis(99000));
    }

    @Test
    void shouldLetUserHomeOverrideBytesDefault() {
        Properties userHome = props(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES, "131072");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), userHome, Map.of());
        assertThat(p.containerPutArchivePipeBufferBytes()).isEqualTo(131072);
    }

    @Test
    void shouldResolveBytesKeyFromEnv() {
        Map<String, String> env = Map.of("ALTCONTAINERS_CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES", "262144");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), env);
        assertThat(p.containerPutArchivePipeBufferBytes()).isEqualTo(262144);
    }

    @Test
    void shouldFailFastOnInvalidValueInUserHome() {
        Properties userHome = props(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "abc");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS);
    }

    @Test
    void shouldFailFastOnNonPositiveValue() {
        Properties userHomeZero = props(AltcontainersProperties.REAPER_STARTUP_TIMEOUT_MS, "0");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHomeZero, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.REAPER_STARTUP_TIMEOUT_MS);

        Properties userHomeNeg = props(AltcontainersProperties.REAPER_STOP_TIMEOUT_MS, "-5");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHomeNeg, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.REAPER_STOP_TIMEOUT_MS);
    }

    @Test
    void shouldFailFastOnInvalidBoolean() {
        Properties userHome = props(AltcontainersProperties.REAPER_DISABLED, "maybe");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.REAPER_DISABLED);
    }

    @Test
    void shouldFailFastOnNonPositiveBytesValue() {
        Properties userHomeZero = props(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES, "0");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHomeZero, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES);

        Properties userHomeNeg = props(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES, "-1");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHomeNeg, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES);
    }

    @Test
    void shouldFailFastOnNonNumericBytesValue() {
        Properties userHome = props(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES, "abc");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.CONTAINER_PUT_ARCHIVE_PIPE_BUFFER_BYTES);
    }

    @Test
    void shouldIgnoreUnknownKeys() {
        Properties userHome = props("altcontainers.bogus", "1");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), userHome, Map.of());
        assertThat(p.reaperConnectionTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    // --- System property resolution tests ---

    @Test
    void shouldResolveSystemPropertyOverClasspathAndEnv() {
        System.setProperty("altcontainers.reaper.connection.timeout.ms", "7777");
        Properties classpath = props(AltcontainersProperties.REAPER_CONNECTION_TIMEOUT_MS, "10000");
        Map<String, String> env = Map.of("ALTCONTAINERS_REAPER_CONNECTION_TIMEOUT_MS", "30000");
        AltcontainersProperties p = AltcontainersProperties.forTesting(classpath, new Properties(), env);
        assertThat(p.reaperConnectionTimeout()).isEqualTo(Duration.ofMillis(7777));
    }

    @Test
    void shouldResolveNetworksParallelismDefault() {
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
        assertThat(p.networksParallelism()).isZero();
    }

    @Test
    void shouldResolveNetworksParallelismFromClasspath() {
        Properties classpath = props(AltcontainersProperties.NETWORKS_PARALLELISM, "4");
        AltcontainersProperties p = AltcontainersProperties.forTesting(classpath, new Properties(), Map.of());
        assertThat(p.networksParallelism()).isEqualTo(4);
    }

    @Test
    void shouldResolveNetworksParallelismFromEnv() {
        Map<String, String> env = Map.of("ALTCONTAINERS_NETWORKS_PARALLELISM", "8");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), env);
        assertThat(p.networksParallelism()).isEqualTo(8);
    }

    @Test
    void shouldResolveNetworksParallelismFromLegacySystemProperty() {
        System.setProperty(AltcontainersProperties.LEGACY_NETWORKS_PARALLELISM, "12");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
        assertThat(p.networksParallelism()).isEqualTo(12);
    }

    @Test
    void shouldPreferNewKeyOverLegacy() {
        Properties classpath = props(AltcontainersProperties.NETWORKS_PARALLELISM, "3");
        System.setProperty(AltcontainersProperties.LEGACY_NETWORKS_PARALLELISM, "5");
        AltcontainersProperties p = AltcontainersProperties.forTesting(classpath, new Properties(), Map.of());
        assertThat(p.networksParallelism()).isEqualTo(3);
    }

    @Test
    void shouldRejectNegativeNetworksParallelism() {
        Properties classpath = props(AltcontainersProperties.NETWORKS_PARALLELISM, "-1");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(classpath, new Properties(), Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.NETWORKS_PARALLELISM);
    }

    @Test
    void shouldRejectNonNumericNetworksParallelism() {
        Properties classpath = props(AltcontainersProperties.NETWORKS_PARALLELISM, "abc");
        assertThatThrownBy(() -> AltcontainersProperties.forTesting(classpath, new Properties(), Map.of()))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining(AltcontainersProperties.NETWORKS_PARALLELISM);
    }

    @Test
    void shouldResolveDockerHostDefault() {
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
        assertThat(p.dockerHost()).isEmpty();
    }

    @Test
    void shouldResolveDockerHostFromEnv() {
        Map<String, String> env = Map.of("ALTCONTAINERS_DOCKER_HOST", "tcp://example.com:2375");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), env);
        assertThat(p.dockerHost()).isEqualTo("tcp://example.com:2375");
    }

    @Test
    void shouldResolveDockerHostFromSystemProperty() {
        System.setProperty("altcontainers.docker.host", "tcp://10.0.0.1:2375");
        AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
        assertThat(p.dockerHost()).isEqualTo("tcp://10.0.0.1:2375");
    }

    private static Properties props(String... keyValuePairs) {
        Properties properties = new Properties();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            properties.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return properties;
    }

    private static void clearSystemPropertyKeys() {
        System.clearProperty("altcontainers.reaper.connection.timeout.ms");
        System.clearProperty("altcontainers.reaper.stop.timeout.ms");
        System.clearProperty("altcontainers.reaper.disabled");
        System.clearProperty(AltcontainersProperties.NETWORKS_PARALLELISM);
        System.clearProperty(AltcontainersProperties.LEGACY_NETWORKS_PARALLELISM);
        System.clearProperty(AltcontainersProperties.DOCKER_HOST);
    }
}
