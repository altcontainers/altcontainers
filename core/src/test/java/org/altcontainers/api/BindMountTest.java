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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BindMount}.
 */
class BindMountTest {

    @Test
    void twoArgumentConstructorShouldDefaultToReadWrite() {
        BindMount bindMount = new BindMount("/host", "/container");

        assertThat(bindMount.hostPath()).isEqualTo("/host");
        assertThat(bindMount.containerPath()).isEqualTo("/container");
        assertThat(bindMount.readOnly()).isFalse();
        assertThat(bindMount).isEqualTo(new BindMount("/host", "/container", false));
    }

    @Test
    void shouldCarryReadOnlyFlag() {
        BindMount bindMount = new BindMount("/host", "/container", true);

        assertThat(bindMount.readOnly()).isTrue();
        assertThat(bindMount).isNotEqualTo(new BindMount("/host", "/container"));
    }

    @Test
    void shouldRejectNullOrBlankHostPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BindMount(null, "/container"))
                .withMessageContaining("hostPath");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BindMount(" ", "/container", true))
                .withMessageContaining("hostPath");
    }

    @Test
    void shouldRejectNullOrBlankContainerPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BindMount("/host", null))
                .withMessageContaining("containerPath");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BindMount("/host", "", true))
                .withMessageContaining("containerPath");
    }
}
