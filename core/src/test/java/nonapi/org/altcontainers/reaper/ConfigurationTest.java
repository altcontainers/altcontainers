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

package nonapi.org.altcontainers.reaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

    private final Map<String, String> classpathProperties = new HashMap<>();

    @AfterEach
    void clearSystemProperties() {
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("altcontainers.reaper.")) {
                System.clearProperty(name);
            }
        }
    }

    @Test
    void loadsDefaultsWhenNoPropertiesOrEnv() {
        Configuration configuration = Configuration.from(Map.of());
        assertThat(configuration.disabled()).isFalse();
        assertThat(configuration.connectionTimeoutMilliseconds()).isEqualTo(10_000L);
        assertThat(configuration.cleanupTimeoutMilliseconds()).isEqualTo(30_000L);
        assertThat(configuration.heartbeatIntervalMilliseconds()).isEqualTo(5_000L);
        assertThat(configuration.sessionTimeoutMilliseconds()).isEqualTo(30_000L);
        assertThat(configuration.idleTimeoutMilliseconds()).isEqualTo(180_000L);
        assertThat(configuration.logLevel()).isEqualTo("INFO");
        assertThat(configuration.daemonLogDirectory()).isNotBlank();
    }

    @Test
    void systemPropertiesOverrideClasspathProperties() {
        System.setProperty("altcontainers.reaper.cleanup.timeout.milliseconds", "9999");
        System.setProperty("altcontainers.reaper.log.level", "ERROR");
        Configuration configuration = Configuration.load(getClass().getClassLoader());
        assertThat(configuration.cleanupTimeoutMilliseconds()).isEqualTo(9999L);
        assertThat(configuration.logLevel()).isEqualTo("ERROR");
    }

    @Test
    void classpathPropertiesAreLoadedFromAltcontainersProperties() {
        Configuration configuration = Configuration.from(classpathProperties);
        assertThat(configuration.disabled()).isFalse();
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        for (long invalid : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> Configuration.from(
                            Map.of("altcontainers.reaper.connection.timeout.milliseconds", String.valueOf(invalid))))
                    .isInstanceOf(ContainerException.class);
            assertThatThrownBy(() -> Configuration.from(
                            Map.of("altcontainers.reaper.cleanup.timeout.milliseconds", String.valueOf(invalid))))
                    .isInstanceOf(ContainerException.class);
            assertThatThrownBy(() -> Configuration.from(
                            Map.of("altcontainers.reaper.heartbeat.interval.milliseconds", String.valueOf(invalid))))
                    .isInstanceOf(ContainerException.class);
            assertThatThrownBy(() -> Configuration.from(
                            Map.of("altcontainers.reaper.session.timeout.milliseconds", String.valueOf(invalid))))
                    .isInstanceOf(ContainerException.class);
        }
    }

    @Test
    void rejectsNegativeIdleTimeout() {
        assertThatThrownBy(() -> Configuration.from(Map.of("altcontainers.reaper.idle.timeout.milliseconds", "-1")))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("idle.timeout");
    }

    @Test
    void acceptsZeroIdleTimeout() {
        Configuration configuration = Configuration.from(Map.of("altcontainers.reaper.idle.timeout.milliseconds", "0"));
        assertThat(configuration.idleTimeoutMilliseconds()).isEqualTo(0L);
    }

    @Test
    void acceptsValidLogLevelsCaseInsensitive() {
        for (String level : new String[] {"OFF", "ERROR", "INFO", "TRACE"}) {
            Configuration configuration = Configuration.from(Map.of("altcontainers.reaper.log.level", level));
            assertThat(configuration.logLevel()).isEqualTo(level);
        }
        for (String level : new String[] {"off", "error", "info", "trace"}) {
            Configuration configuration = Configuration.from(Map.of("altcontainers.reaper.log.level", level));
            assertThat(configuration.logLevel()).isEqualTo(level.toUpperCase());
        }
    }

    @Test
    void rejectsInvalidLogLevel() {
        assertThatThrownBy(() -> Configuration.from(Map.of("altcontainers.reaper.log.level", "INVALID")))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("log.level");
    }

    @Test
    void disabledAndPrivilegedBooleansAreParsed() {
        Configuration configuration = Configuration.from(Map.of("altcontainers.reaper.disabled", "true"));
        assertThat(configuration.disabled()).isTrue();
    }

    @Test
    void rejectsNullLogLevel() {
        assertThatThrownBy(() -> new Configuration(
                        false, 1_000L, 1_000L, 3_000L, 5_000L, 0L, null, System.getProperty("java.io.tmpdir")))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("log.level");
    }

    @Test
    void rejectsNullDaemonLogDirectory() {
        assertThatThrownBy(() -> new Configuration(false, 1_000L, 1_000L, 3_000L, 5_000L, 0L, "INFO", null))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("log.directory");
    }

    @Test
    void rejectsBlankDaemonLogDirectory() {
        assertThatThrownBy(() -> new Configuration(false, 1_000L, 1_000L, 3_000L, 5_000L, 0L, "INFO", "   "))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("log.directory");
    }

    @Test
    void rejectsNonNumericTimeout() {
        assertThatThrownBy(() -> Configuration.from(
                        Map.of("altcontainers.reaper.connection.timeout.milliseconds", "not-a-number")))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("connection.timeout.milliseconds")
                .hasMessageContaining("not-a-number");
    }

    @Test
    void treatsBlankTimeoutAsDefault() {
        Configuration configuration =
                Configuration.from(Map.of("altcontainers.reaper.connection.timeout.milliseconds", "   "));
        assertThat(configuration.connectionTimeoutMilliseconds()).isEqualTo(10_000L);
    }

    @Test
    void treatsBlankDisabledFlagAsDefault() {
        Configuration configuration = Configuration.from(Map.of("altcontainers.reaper.disabled", "   "));
        assertThat(configuration.disabled()).isFalse();
    }

    @Test
    void parsesExplicitDisabledFalseFlag() {
        Configuration configuration = Configuration.from(Map.of("altcontainers.reaper.disabled", "false"));
        assertThat(configuration.disabled()).isFalse();
    }

    @Test
    void loadsClasspathPropertiesWhenResourcePresent() {
        String content = "altcontainers.reaper.disabled=true\n" + "altcontainers.reaper.log.level=TRACE\n";
        ClassLoader loader = classLoaderServingProperties(content);

        Configuration configuration = Configuration.load(loader);

        assertThat(configuration.disabled()).isTrue();
        assertThat(configuration.logLevel()).isEqualTo("TRACE");
    }

    @Test
    void ignoresClasspathPropertiesWhenResourceIsUnreadable() {
        ClassLoader loader = classLoaderServingUnreadableProperties();

        Configuration configuration = Configuration.load(loader);

        // IOException during load is swallowed -> defaults apply even though the resource "exists".
        assertThat(configuration.disabled()).isFalse();
        assertThat(configuration.logLevel()).isEqualTo("INFO");
    }

    @Test
    void loadFallsBackToDefiningClassLoaderWhenContextClassLoaderIsNull() {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            Configuration configuration = Configuration.load();

            assertThat(configuration.daemonLogDirectory()).isNotBlank();
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    @Test
    void loadToleratesNullClassLoaderArgument() {
        // Explicit null arg exercises the defining-classloader fallback in loadClasspathProperties.
        Configuration configuration = Configuration.load((ClassLoader) null);

        assertThat(configuration.daemonLogDirectory()).isNotBlank();
    }

    private static ClassLoader classLoaderServingProperties(String content) {
        InputStream resource = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        return new ClassLoader(ConfigurationTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("altcontainers.properties".equals(name)) {
                    return resource;
                }
                return super.getResourceAsStream(name);
            }
        };
    }

    private static ClassLoader classLoaderServingUnreadableProperties() {
        InputStream resource = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken resource");
            }
        };
        return new ClassLoader(ConfigurationTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("altcontainers.properties".equals(name)) {
                    return resource;
                }
                return super.getResourceAsStream(name);
            }
        };
    }

    @Test
    void rejectsSessionTimeoutShorterThanHeartbeatInterval() {
        Map<String, String> props = Map.of(
                "altcontainers.reaper.session.timeout.milliseconds", "3000",
                "altcontainers.reaper.heartbeat.interval.milliseconds", "5000");

        assertThatThrownBy(() -> Configuration.from(props))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("must be greater than");
    }

    @Test
    void rejectsSessionTimeoutEqualToHeartbeatInterval() {
        Map<String, String> props = Map.of(
                "altcontainers.reaper.session.timeout.milliseconds", "5000",
                "altcontainers.reaper.heartbeat.interval.milliseconds", "5000");

        assertThatThrownBy(() -> Configuration.from(props))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("must be greater than");
    }
}
