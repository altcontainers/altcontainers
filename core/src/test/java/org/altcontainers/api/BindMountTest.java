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

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class BindMountTest {

    @Test
    void shouldRejectBlankHostPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BindMount("", "/container"));
    }

    @Test
    void shouldRejectNullHostPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BindMount(null, "/container"))
                .withMessage("hostPath must not be blank");
    }

    @Test
    void shouldRejectBlankContainerPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BindMount("/host", ""));
    }

    @Test
    void shouldRejectNullContainerPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BindMount("/host", null))
                .withMessage("containerPath must not be blank");
    }
}
