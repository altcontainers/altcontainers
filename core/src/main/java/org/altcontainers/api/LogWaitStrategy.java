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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A readiness strategy that counts log lines matching a regex
 * (with {@link Pattern#DOTALL}) applied against raw (newline-terminated)
 * log line content. Stateful; each startup attempt creates a
 * fresh counter via {@link #newAttemptCondition()}.
 *
 * <p>Created via {@link #builder()} or {@link Wait#forLogMessage(String, int)}.
 *
 * <pre>{@code
 * // Builder
 * WaitStrategy log = LogWaitStrategy.builder().pattern(".*started.*").build();
 *
 * // Factory
 * WaitStrategy log = Wait.forLogMessage(".*started.*", 1);
 * }</pre>
 */
public final class LogWaitStrategy implements WaitStrategy {

    private final Pattern pattern;
    private final int times;
    private final AtomicInteger count;

    /**
     * Creates a log-wait strategy.
     *
     * @param regex the whole-string regular expression, compiled with
     *     {@link Pattern#DOTALL}; must not be {@code null} or blank
     * @param times the number of matching lines required; must be
     *     {@code >= 1}
     * @throws IllegalArgumentException if {@code regex} is {@code null} or
     *     blank, or if {@code times} is {@code < 1}
     */
    public LogWaitStrategy(String regex, int times) {
        if (regex == null || regex.isBlank()) {
            throw new IllegalArgumentException("regex must not be blank");
        }
        if (times < 1) {
            throw new IllegalArgumentException("times must be >= 1, was " + times);
        }
        this.pattern = Pattern.compile(regex, Pattern.DOTALL);
        this.times = times;
        this.count = new AtomicInteger(0);
    }

    /**
     * Private constructor for cloning with the same pattern and required count
     * but a fresh counter.
     *
     * @param pattern the compiled pattern
     * @param times the number of matching lines required
     * @param count the counter instance
     */
    private LogWaitStrategy(Pattern pattern, int times, AtomicInteger count) {
        this.pattern = pattern;
        this.times = times;
        this.count = count;
    }

    /**
     * Returns a new instance with the same pattern and required count but a
     * fresh counter, so stale log lines from prior attempts cannot satisfy a
     * new attempt.
     *
     * @return a new strategy instance with a fresh counter
     */
    @Override
    public WaitStrategy newAttemptCondition() {
        return new LogWaitStrategy(pattern, times, new AtomicInteger(0));
    }

    /**
     * Returns a consumer that increments the match count for lines matching
     * the pattern; used by the framework for log dispatch.
     *
     * @return a log-line consumer
     */
    @Override
    public Consumer<String> logLineConsumer() {
        return this::incrementIfMatches;
    }

    /**
     * Returns whether the required number of matching log lines has been
     * observed.
     *
     * @param container the container to evaluate against
     * @return {@code true} if the count has reached the required number
     */
    @Override
    public boolean check(Container container) {
        return count.get() >= times;
    }

    /**
     * Increments the match count if the given raw line matches the pattern.
     *
     * <p>The raw log line is matched as-is (including any trailing newline),
     * matching Testcontainers' behavior. Patterns compiled with
     * {@link Pattern#DOTALL} allow {@code .} to match {@code \n}, so
     * wildcard patterns like {@code ".*started.*"} continue to work.
     *
     * @param line the raw (newline-terminated) log line
     */
    void incrementIfMatches(String line) {
        if (pattern.matcher(line).matches()) {
            count.incrementAndGet();
        }
    }

    /**
     * Returns a new builder for configuring a log-wait strategy.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for configuring a {@link LogWaitStrategy}.
     *
     * <p>Instances are mutable and not thread-safe.
     */
    public static final class Builder {

        private String pattern;
        private int times = 1;

        /**
         * Creates a new builder. Use {@link LogWaitStrategy#builder()}
         * instead of calling this constructor directly.
         */
        private Builder() {
            // Intentionally empty
        }

        /**
         * Sets the whole-string regular expression, compiled with
         * {@link Pattern#DOTALL}.
         *
         * @param pattern the regular expression; must not be {@code null}
         *     or blank
         * @return this builder
         */
        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        /**
         * Sets the number of matching lines required. Default is 1.
         *
         * @param times the required match count; must be {@code >= 1}
         * @return this builder
         */
        public Builder times(int times) {
            this.times = times;
            return this;
        }

        /**
         * Builds an immutable {@link LogWaitStrategy}.
         *
         * @return a new log-wait strategy
         * @throws IllegalArgumentException if {@code pattern} is
         *     {@code null} or blank, or if {@code times} is {@code < 1}
         */
        public LogWaitStrategy build() {
            return new LogWaitStrategy(pattern, times);
        }
    }
}
