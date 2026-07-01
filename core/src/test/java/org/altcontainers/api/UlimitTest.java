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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class UlimitTest {

    @Test
    void shouldCreateValidUlimit() {
        Ulimit ulimit = new Ulimit("nofile", 65536, 65536);

        assertThat(ulimit.name()).isEqualTo("nofile");
        assertThat(ulimit.soft()).isEqualTo(65536);
        assertThat(ulimit.hard()).isEqualTo(65536);
    }

    @Test
    void shouldRejectNullName() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Ulimit(null, 65536, 65536))
                .withMessage("name must not be null");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("", 65536, 65536))
                .withMessage("name must not be blank");
    }

    @Test
    void shouldRejectWhitespaceName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("  ", 65536, 65536))
                .withMessage("name must not be blank");
    }

    @Test
    void shouldRejectNegativeSoft() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("nofile", -1, 65536))
                .withMessage("soft must be >= 0, was -1");
    }

    @Test
    void shouldRejectNegativeHard() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("nofile", 65536, -1))
                .withMessage("hard must be >= 0, was -1");
    }

    @Test
    void shouldAllowZeroSoft() {
        Ulimit ulimit = new Ulimit("nofile", 0, 65536);
        assertThat(ulimit.soft()).isEqualTo(0);
    }

    @Test
    void shouldAllowZeroHard() {
        Ulimit ulimit = new Ulimit("nofile", 65536, 0);
        assertThat(ulimit.hard()).isEqualTo(0);
    }

    @Test
    void shouldImplementEquality() {
        Ulimit ulimit1 = new Ulimit("nofile", 65536, 65536);
        Ulimit ulimit2 = new Ulimit("nofile", 65536, 65536);

        assertThat(ulimit1).isEqualTo(ulimit2);
        assertThat(ulimit1.hashCode()).isEqualTo(ulimit2.hashCode());
    }

    @Test
    void shouldImplementToString() {
        Ulimit ulimit = new Ulimit("nofile", 65536, 65536);

        assertThat(ulimit.toString()).isNotNull().contains("nofile").contains("65536");
    }

    @Test
    void shouldRejectSoftGreaterThanHard() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("nofile", 65536, 4096))
                .withMessageContaining("soft must be <= hard");
    }

    @Test
    void shouldAllowSoftEqualToHard() {
        Ulimit ulimit = new Ulimit("nofile", 65536, 65536);
        assertThat(ulimit.soft()).isEqualTo(65536);
        assertThat(ulimit.hard()).isEqualTo(65536);
    }

    @Test
    void shouldAllowSoftLessThanHard() {
        Ulimit ulimit = new Ulimit("nofile", 4096, 65536);
        assertThat(ulimit.soft()).isEqualTo(4096);
        assertThat(ulimit.hard()).isEqualTo(65536);
    }

    @Test
    void shouldAllowSoftWithZeroHard() {
        Ulimit ulimit = new Ulimit("nofile", 65536, 0);
        assertThat(ulimit.soft()).isEqualTo(65536);
        assertThat(ulimit.hard()).isEqualTo(0);
    }
}
