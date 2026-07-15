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

package examples.altcontainers.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.altcontainers.api.OutputFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContainerConsumer}.
 */
class ContainerConsumerTest {

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
    void shouldPrintPrefixFormat() {
        ContainerConsumer consumer = ContainerConsumer.of("TEST", "image:latest");
        consumer.accept(new OutputFrame(OutputFrame.Type.STDOUT, "hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(outContent.toString().trim()).isEqualTo("[TEST] image:latest | hello");
    }

    @Test
    void shouldSuppressBlankSafeOutput() {
        ContainerConsumer consumer = ContainerConsumer.of("TEST", "image:latest");
        consumer.accept(new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x00, 0x07, 0x1B}));
        assertThat(outContent.toString()).isEmpty();
    }

    @Test
    void shouldPrintOnlySafeText() {
        ContainerConsumer consumer = ContainerConsumer.of("TEST", "image:latest");
        consumer.accept(new OutputFrame(OutputFrame.Type.STDOUT, "hel\033[31mlo\0".getBytes(StandardCharsets.UTF_8)));
        assertThat(outContent.toString()).contains("hello");
        assertThat(outContent.toString()).doesNotContain("\u001B");
        assertThat(outContent.toString()).doesNotContain("\0");
    }

    @Test
    void shouldPreservePrintableUnicode() {
        ContainerConsumer consumer = ContainerConsumer.of("TEST", "image:latest");
        consumer.accept(new OutputFrame(OutputFrame.Type.STDOUT, "caf\u00e9".getBytes(StandardCharsets.UTF_8)));
        assertThat(outContent.toString()).contains("caf\u00e9");
    }

    @Test
    void shouldNotContainNulInOutputBytes() {
        ContainerConsumer consumer = ContainerConsumer.of("TEST", "image:latest");
        consumer.accept(new OutputFrame(OutputFrame.Type.STDOUT, new byte[] {0x61, 0x00, 0x62}));
        assertThat(outContent.toByteArray()).doesNotContain((byte) 0x00);
    }
}
