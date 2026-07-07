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

/**
 * Tests for {@link Ulimit}.
 */
class UlimitTest {

    @Test
    void shouldConstructValidUlimit() {
        Ulimit ulimit = new Ulimit("nofile", 1024, 65536);

        assertThat(ulimit.name()).isEqualTo("nofile");
        assertThat(ulimit.soft()).isEqualTo(1024);
        assertThat(ulimit.hard()).isEqualTo(65536);
    }

    @Test
    void shouldAllowEqualSoftAndHardLimits() {
        Ulimit ulimit = new Ulimit("nofile", 65536, 65536);

        assertThat(ulimit.soft()).isEqualTo(65536);
        assertThat(ulimit.hard()).isEqualTo(65536);
    }

    @Test
    void shouldAllowSoftLessThanHard() {
        Ulimit ulimit = new Ulimit("nofile", 1024, 65536);

        assertThat(ulimit.soft()).isLessThan(ulimit.hard());
    }

    @Test
    void shouldAllowHardOfZero() {
        Ulimit ulimit = new Ulimit("nofile", 0, 0);

        assertThat(ulimit.soft()).isEqualTo(0);
        assertThat(ulimit.hard()).isEqualTo(0);
    }

    @Test
    void shouldAllowSoftOfZeroWithPositiveHard() {
        Ulimit ulimit = new Ulimit("nofile", 0, 1024);

        assertThat(ulimit.soft()).isEqualTo(0);
        assertThat(ulimit.hard()).isEqualTo(1024);
    }

    @Test
    void shouldRejectNullName() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Ulimit(null, 1024, 65536))
                .withMessage("name must not be null");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("  ", 1024, 65536))
                .withMessage("name must not be blank");
    }

    @Test
    void shouldRejectEmptyName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("", 1024, 65536))
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
                .isThrownBy(() -> new Ulimit("nofile", 1024, -1))
                .withMessage("hard must be >= 0, was -1");
    }

    @Test
    void shouldRejectSoftGreaterThanHardWhenHardPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Ulimit("nofile", 65536, 1024))
                .withMessage("soft must be <= hard when hard > 0, was soft=65536, hard=1024");
    }

    @Test
    void shouldAllowSoftGreaterThanHardWhenHardIsZero() {
        Ulimit ulimit = new Ulimit("nproc", 10, 0);

        assertThat(ulimit.soft()).isEqualTo(10);
        assertThat(ulimit.hard()).isEqualTo(0);
    }
}
