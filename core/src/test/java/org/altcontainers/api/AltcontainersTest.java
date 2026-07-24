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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Altcontainers} per-property precedence and
 * {@link #isExplicitlySet(String)} tracking.
 */
class AltcontainersTest {

    @BeforeEach
    void setUp() {
        Altcontainers.configure(null);
    }

    @AfterEach
    void tearDown() {
        Altcontainers.configure(null);
    }

    @Test
    void shouldReturnFalseBeforeConfigure() {
        assertThat(Altcontainers.isExplicitlySet("any.key")).isFalse();
    }

    @Test
    void shouldTrackOnlyExplicitlySetKeys() {
        Altcontainers.configure(c -> c.reaperDisabled(true));
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.disabled"))
                .isTrue();
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.connection.timeout.ms"))
                .isFalse();
    }

    @Test
    void shouldClearOnConfigureNull() {
        Altcontainers.configure(c -> c.reaperDisabled(true));
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.disabled"))
                .isTrue();
        Altcontainers.configure(null);
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.disabled"))
                .isFalse();
    }

    @Test
    void shouldReturnConfigurationEvenWhenNoExplicitKeys() {
        Altcontainers.configure(c -> {});
        assertThat(Altcontainers.configuration()).isNotNull();
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.disabled"))
                .isFalse();
    }

    @Test
    void shouldPerPropertyPrecedenceWork() {
        Altcontainers.configure(c -> c.reaperDisabled(true));
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.disabled"))
                .isTrue();
        assertThat(Altcontainers.isExplicitlySet("altcontainers.reaper.connection.timeout.ms"))
                .isFalse();
    }
}
