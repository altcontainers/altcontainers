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

import org.altcontainers.api.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reaper.exitMessage()")
class ReaperExitMessageTest {

    @Test
    @DisplayName("exitMessage(0) includes version and exit-code=[0]")
    void exitMessageCodeZero() {
        String result = Reaper.exitMessage(0);

        assertThat(result).isEqualTo("Altcontainers Reaper v" + Version.version() + " exit-code=[0]");
    }

    @Test
    @DisplayName("exitMessage(1) includes version and exit-code=[1]")
    void exitMessageCodeOne() {
        String result = Reaper.exitMessage(1);

        assertThat(result).isEqualTo("Altcontainers Reaper v" + Version.version() + " exit-code=[1]");
    }

    @Test
    @DisplayName("exitMessage output contains Version.version() and not \"UNKNOWN\"")
    void exitMessageContainsActualVersion() {
        String result = Reaper.exitMessage(0);

        assertThat(result).contains(Version.version());
        assertThat(result).doesNotContain("UNKNOWN");
    }

    @Test
    @DisplayName("exitMessage output does not contain the old 'exiting' format")
    void exitMessageDoesNotContainOldFormat() {
        String result = Reaper.exitMessage(0);

        assertThat(result).doesNotContain("exiting [");
    }
}
