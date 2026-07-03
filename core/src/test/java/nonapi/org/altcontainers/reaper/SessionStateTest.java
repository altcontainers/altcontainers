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

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SessionStateTest {

    @Test
    void rejectsBlankSessionId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SessionState("", 1000L, new AtomicLong(0), null))
                .withMessageContaining("sessionId must not be blank");
    }

    @Test
    void rejectsZeroHeartbeatTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SessionState("id", 0L, new AtomicLong(0), null))
                .withMessageContaining("heartbeatTimeoutMs must be positive");
    }

    @Test
    void rejectsNegativeHeartbeatTimeout() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SessionState("id", -1L, new AtomicLong(0), null));
    }

    @Test
    void rejectsNullLastHeartbeatNanos() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SessionState("id", 1000L, null, null))
                .withMessageContaining("lastHeartbeatNanos");
    }
}
