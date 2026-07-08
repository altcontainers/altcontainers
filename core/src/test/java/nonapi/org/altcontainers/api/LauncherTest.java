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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Launcher} command construction and property forwarding.
 */
class LauncherTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("altcontainers.reaper.connection.timeout.ms");
    }

    @Test
    void shouldPassResolvedReaperTimeoutsAsExplicitArgs() {
        List<String> cmd = Launcher.buildCommand(
                "session-123", Path.of("reaper.jar"), "java", false, Duration.ofSeconds(12), Duration.ofSeconds(7));
        assertThat(cmd).contains("-Daltcontainers.reaper.session.id=session-123");
        assertThat(cmd).contains("-Daltcontainers.reaper.connection.timeout.ms=12000");
        assertThat(cmd).contains("-Daltcontainers.reaper.stop.timeout.ms=7000");
        assertThat(cmd).contains("-jar");
        assertThat(cmd).contains("reaper.jar");
    }

    @Test
    void shouldNotForwardTimeoutKeysFromSystemProperties() {
        System.setProperty("altcontainers.reaper.connection.timeout.ms", "999");
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.connection.timeout.ms"))
                .isFalse();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.startup.timeout.ms"))
                .isFalse();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.stop.timeout.ms"))
                .isFalse();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.log.directory"))
                .isTrue();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.reaper.log.level"))
                .isTrue();
        assertThat(Launcher.shouldForwardSystemProperty("altcontainers.docker.host"))
                .isTrue();
    }
}
