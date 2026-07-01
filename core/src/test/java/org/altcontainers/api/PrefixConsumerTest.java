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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrefixConsumerTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void shouldPrintFormattedLine() {
        PrefixConsumer.of("PREFIX", "image:tag").accept("hello");
        assertThat(outContent.toString()).contains("[PREFIX] image:tag | hello");
    }

    @Test
    void shouldIgnoreNullInput() {
        PrefixConsumer.of("PREFIX", "image:tag").accept(null);
        assertThat(outContent.toString()).isEmpty();
    }

    @Test
    void shouldIgnoreBlankInput() {
        PrefixConsumer.of("PREFIX", "image:tag").accept("   ");
        assertThat(outContent.toString()).isEmpty();
    }

    @Test
    void shouldRejectNullPrefix() {
        assertThatNullPointerException()
                .isThrownBy(() -> PrefixConsumer.of(null, "img"))
                .withMessage("prefix");
    }

    @Test
    void shouldRejectNullImage() {
        assertThatNullPointerException()
                .isThrownBy(() -> PrefixConsumer.of("pfx", null))
                .withMessage("image");
    }
}
