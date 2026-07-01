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
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@DisplayName("Reaper.configureLogback() rolling file appender")
class ReaperLogRollingTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
    }

    @Test
    @DisplayName("configureLogback creates a RollingFileAppender, not a FileAppender")
    void configureLogbackCreatesRollingFileAppender() {
        String logPath = tempDir.resolve("reaper-12345.log").toString();
        assertThat(Files.exists(Path.of(logPath)))
                .describedAs("log file must not exist before configureLogback")
                .isFalse();

        Reaper.configureLogback(logPath);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        var appenders = new ArrayList<Appender<ILoggingEvent>>();
        rootLogger.iteratorForAppenders().forEachRemaining(appenders::add);

        assertThat(appenders)
                .describedAs("root logger must have exactly one appender")
                .hasSize(1);

        Appender<ILoggingEvent> appender = appenders.get(0);
        assertThat(appender)
                .describedAs("appender must be a RollingFileAppender")
                .isInstanceOf(RollingFileAppender.class);

        RollingFileAppender<ILoggingEvent> rollingAppender = (RollingFileAppender<ILoggingEvent>) appender;

        assertThat(rollingAppender.getTriggeringPolicy())
                .describedAs("triggering policy must be SizeBasedTriggeringPolicy")
                .isInstanceOf(SizeBasedTriggeringPolicy.class);

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy =
                (SizeBasedTriggeringPolicy<ILoggingEvent>) rollingAppender.getTriggeringPolicy();
        assertThat(triggeringPolicy.getMaxFileSize().getSize())
                .describedAs("max file size must be 1MB (1048576 bytes)")
                .isEqualTo(FileSize.valueOf("1MB").getSize());

        assertThat(rollingAppender.getRollingPolicy())
                .describedAs("rolling policy must be FixedWindowRollingPolicy")
                .isInstanceOf(FixedWindowRollingPolicy.class);

        FixedWindowRollingPolicy rollingPolicy = (FixedWindowRollingPolicy) rollingAppender.getRollingPolicy();
        assertThat(rollingPolicy.getMinIndex())
                .describedAs("min index must be 1")
                .isEqualTo(1);
        assertThat(rollingPolicy.getMaxIndex())
                .describedAs("max index must be 9")
                .isEqualTo(9);

        assertThat(rollingAppender.getEncoder())
                .describedAs("encoder must be a PatternLayoutEncoder")
                .isInstanceOf(PatternLayoutEncoder.class);

        PatternLayoutEncoder encoder = (PatternLayoutEncoder) rollingAppender.getEncoder();
        assertThat(encoder.getPattern())
                .describedAs("encoder pattern must match production format")
                .isEqualTo("%d{yyyy-MM-dd HH:mm:ss.SSS} | %level | %logger | %msg%n");
    }

    @Test
    @DisplayName("configureLogback applies log level from Configuration")
    void configureLogbackPreservesLogLevel() {
        String logPath = tempDir.resolve("reaper-12345.log").toString();

        Reaper.configureLogback(logPath);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        Configuration config = Configuration.load();
        Level expectedLevel = Level.toLevel(config.logLevel(), Level.INFO);

        assertThat(rootLogger.getLevel())
                .describedAs("root logger level must match Configuration.logLevel()")
                .isEqualTo(expectedLevel);
    }

    @Test
    @DisplayName("configureLogback does not throw when log directory is unwritable")
    void configureLogbackBestEffortOnUnwritableDirectory() {
        String logPath = tempDir.resolve("nonexistent").resolve("reaper.log").toString();

        assertThatCode(() -> Reaper.configureLogback(logPath))
                .describedAs("configureLogback must not throw when log directory is unwritable")
                .doesNotThrowAnyException();
    }
}
