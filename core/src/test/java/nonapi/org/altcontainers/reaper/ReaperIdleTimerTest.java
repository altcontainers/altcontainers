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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Reaper#isDaemonActive(int, int)} (Finding 1 — idle-timer race fix).
 */
class ReaperIdleTimerTest {

    @Test
    void isDaemonActiveReturnsFalseWhenBothCountersAreZero() {
        assertThat(Reaper.isDaemonActive(0, 0)).isFalse();
    }

    @Test
    void isDaemonActiveReturnsTrueWhenSessionsConnected() {
        assertThat(Reaper.isDaemonActive(1, 0)).isTrue();
    }

    @Test
    void isDaemonActiveReturnsTrueWhenConnectionPending() {
        assertThat(Reaper.isDaemonActive(0, 1)).isTrue();
    }

    @Test
    void isDaemonActiveReturnsTrueWhenBothCountersNonZero() {
        assertThat(Reaper.isDaemonActive(1, 1)).isTrue();
    }
}
