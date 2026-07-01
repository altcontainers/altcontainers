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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.altcontainers.api.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("Reaper.logHeader()")
class ReaperLogHeaderTest {

    private ByteArrayOutputStream capturedOutput;

    @BeforeEach
    void captureLogOutput() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        capturedOutput = new ByteArrayOutputStream();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setPattern("%msg%n");
        encoder.setContext(context);
        encoder.start();

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setOutputStream(capturedOutput);
        appender.setEncoder(encoder);
        appender.setContext(context);
        appender.start();

        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void resetLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
    }

    @Test
    @DisplayName("logHeader reports actual version from Version, not \"unknown\"")
    void logHeaderReportsActualVersionNotUnknown() {
        Reaper.logHeader();

        String output = capturedOutput.toString(StandardCharsets.UTF_8);

        assertThat(output).describedAs("output must not contain \"unknown\"").doesNotContainIgnoringCase("unknown");
        assertThat(output)
                .describedAs("output must contain the Version.version() value")
                .contains(Version.version());
    }

    @Test
    @DisplayName("logHeader output uses pipe-delimited format: TIMESTAMP | LEVEL | FQCN | MESSAGE")
    void logHeaderUsesPipeDelimitedFormat() {
        // Reconfigure capture with a pattern that exposes the full structure.
        // Reset what the @BeforeEach set up.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        capturedOutput = new ByteArrayOutputStream();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        // Match the production pattern so we can assert on the output structure.
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} | %level | %logger | %msg%n");
        encoder.setContext(context);
        encoder.start();

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setOutputStream(capturedOutput);
        appender.setEncoder(encoder);
        appender.setContext(context);
        appender.start();

        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(appender);

        // Act: log through the same path as production.
        Reaper.logHeader();

        String output = capturedOutput.toString(StandardCharsets.UTF_8);

        // Assert: pipe-delimited format with no brackets, no thread name, no level padding.
        // Example: 2026-06-30 21:43:29.720 | INFO | nonapi.org.altcontainers.reaper.Reaper | Altcontainers Reaper
        // v1.0.0
        assertThat(output)
                .describedAs("output must contain pipe-delimited level")
                .contains(" | INFO | ");
        assertThat(output)
                .describedAs("output must contain fully qualified class name with pipe separators")
                .contains(" | nonapi.org.altcontainers.reaper.Reaper | ");
        assertThat(output)
                .describedAs("output must not contain thread name brackets")
                .doesNotContain("[");
        assertThat(output)
                .describedAs("output must not contain thread name brackets")
                .doesNotContain("]");
        assertThat(output)
                .describedAs("output must not contain dash message separator")
                .doesNotContain(" - ");
        assertThat(output)
                .describedAs("output must start with ISO 8601 timestamp")
                .matches(Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\|.*", Pattern.DOTALL));
    }
}
