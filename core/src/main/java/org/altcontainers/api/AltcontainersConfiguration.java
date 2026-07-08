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

import java.time.Duration;
import java.util.Objects;

/**
 * Programmatic Altcontainers runtime configuration.
 *
 * <p>Created via {@link AltcontainersConfiguration.Builder} and passed to
 * {@link Altcontainers#configure(java.util.function.Consumer)}. When set, it is
 * the highest-priority override for every config value, above environment
 * variables, {@code ~/.altcontainers.properties}, and the bundled classpath
 * defaults.
 *
 * @param reaperDisabled whether the reaper is disabled
 * @param reaperConnectionTimeout the reaper connection/handshake timeout
 * @param reaperStartupTimeout the reaper startup timeout
 * @param reaperStopTimeout the reaper stop timeout
 * @param containerStartupTimeout the default container startup timeout
 * @param containerReadinessPollInitial the initial readiness poll interval
 * @param containerReadinessPollMax the maximum readiness poll interval
 * @param containerStartupRetryBackoffMultiplier the startup retry backoff multiplier
 * @param containerStartupRetryBackoffMax the startup retry backoff maximum
 * @param portProbeTimeout the port probe timeout
 * @param httpProbeTimeout the HTTP probe timeout
 * @param containerPutArchivePipeBufferBytes the put-archive pipe buffer size in bytes
 */
public record AltcontainersConfiguration(
        boolean reaperDisabled,
        Duration reaperConnectionTimeout,
        Duration reaperStartupTimeout,
        Duration reaperStopTimeout,
        Duration containerStartupTimeout,
        Duration containerReadinessPollInitial,
        Duration containerReadinessPollMax,
        Duration containerStartupRetryBackoffMultiplier,
        Duration containerStartupRetryBackoffMax,
        Duration portProbeTimeout,
        Duration httpProbeTimeout,
        int containerPutArchivePipeBufferBytes) {

    /**
     * Compact canonical constructor that validates fields.
     *
     * @param reaperDisabled whether the reaper is disabled
     * @param reaperConnectionTimeout the reaper connection/handshake timeout
     * @param reaperStartupTimeout the reaper startup timeout
     * @param reaperStopTimeout the reaper stop timeout
     * @param containerStartupTimeout the default container startup timeout
     * @param containerReadinessPollInitial the initial readiness poll interval
     * @param containerReadinessPollMax the maximum readiness poll interval
     * @param containerStartupRetryBackoffMultiplier the startup retry backoff multiplier
     * @param containerStartupRetryBackoffMax the startup retry backoff maximum
     * @param portProbeTimeout the port probe timeout
     * @param httpProbeTimeout the HTTP probe timeout
     * @param containerPutArchivePipeBufferBytes the put-archive pipe buffer size in bytes
     */
    public AltcontainersConfiguration {
        requirePositive(reaperConnectionTimeout, "reaperConnectionTimeout");
        requirePositive(reaperStartupTimeout, "reaperStartupTimeout");
        requirePositive(reaperStopTimeout, "reaperStopTimeout");
        requirePositive(containerStartupTimeout, "containerStartupTimeout");
        requirePositive(containerReadinessPollInitial, "containerReadinessPollInitial");
        requirePositive(containerReadinessPollMax, "containerReadinessPollMax");
        requirePositive(containerStartupRetryBackoffMultiplier, "containerStartupRetryBackoffMultiplier");
        requirePositive(containerStartupRetryBackoffMax, "containerStartupRetryBackoffMax");
        requirePositive(portProbeTimeout, "portProbeTimeout");
        requirePositive(httpProbeTimeout, "httpProbeTimeout");
        requirePositive(containerPutArchivePipeBufferBytes, "containerPutArchivePipeBufferBytes");
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * Returns a new builder with default values.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for configuring an {@link AltcontainersConfiguration}.
     *
     * <p>Default values match the bundled classpath defaults. Instances are
     * mutable and not thread-safe.
     */
    public static final class Builder {

        private boolean reaperDisabled;
        private Duration reaperConnectionTimeout = Duration.ofSeconds(10);
        private Duration reaperStartupTimeout = Duration.ofSeconds(10);
        private Duration reaperStopTimeout = Duration.ofSeconds(5);
        private Duration containerStartupTimeout = ContainerSpec.DEFAULT_STARTUP_TIMEOUT;
        private Duration containerReadinessPollInitial = Duration.ofMillis(10);
        private Duration containerReadinessPollMax = Duration.ofMillis(500);
        private Duration containerStartupRetryBackoffMultiplier = Duration.ofMillis(1000);
        private Duration containerStartupRetryBackoffMax = Duration.ofMillis(5000);
        private Duration portProbeTimeout = Duration.ofMillis(500);
        private Duration httpProbeTimeout = Duration.ofMillis(2000);
        private int containerPutArchivePipeBufferBytes = 65536;

        /**
         * Creates a new builder. Use {@link AltcontainersConfiguration#builder()}
         * instead of calling this constructor directly.
         */
        private Builder() {
            // Intentionally empty
        }

        /**
         * Sets whether the reaper is disabled.
         *
         * @param reaperDisabled whether the reaper is disabled
         * @return this builder
         */
        public Builder reaperDisabled(boolean reaperDisabled) {
            this.reaperDisabled = reaperDisabled;
            return this;
        }

        /**
         * Sets the reaper connection/handshake timeout.
         *
         * @param timeout the connection timeout; must be positive
         * @return this builder
         */
        public Builder reaperConnectionTimeout(Duration timeout) {
            this.reaperConnectionTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Sets the reaper startup timeout.
         *
         * @param timeout the startup timeout; must be positive
         * @return this builder
         */
        public Builder reaperStartupTimeout(Duration timeout) {
            this.reaperStartupTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Sets the reaper stop timeout.
         *
         * @param timeout the stop timeout; must be positive
         * @return this builder
         */
        public Builder reaperStopTimeout(Duration timeout) {
            this.reaperStopTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Sets the default container startup timeout.
         *
         * @param timeout the startup timeout; must be positive
         * @return this builder
         */
        public Builder containerStartupTimeout(Duration timeout) {
            this.containerStartupTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Sets the initial readiness poll interval.
         *
         * @param interval the initial readiness poll interval; must be positive
         * @return this builder
         */
        public Builder containerReadinessPollInitial(Duration interval) {
            this.containerReadinessPollInitial = Objects.requireNonNull(interval, "interval must not be null");
            return this;
        }

        /**
         * Sets the maximum readiness poll interval.
         *
         * @param interval the maximum readiness poll interval; must be positive
         * @return this builder
         */
        public Builder containerReadinessPollMax(Duration interval) {
            this.containerReadinessPollMax = Objects.requireNonNull(interval, "interval must not be null");
            return this;
        }

        /**
         * Sets the startup retry backoff multiplier.
         *
         * @param multiplier the startup retry backoff multiplier; must be positive
         * @return this builder
         */
        public Builder containerStartupRetryBackoffMultiplier(Duration multiplier) {
            this.containerStartupRetryBackoffMultiplier =
                    Objects.requireNonNull(multiplier, "multiplier must not be null");
            return this;
        }

        /**
         * Sets the startup retry backoff maximum.
         *
         * @param max the startup retry backoff maximum; must be positive
         * @return this builder
         */
        public Builder containerStartupRetryBackoffMax(Duration max) {
            this.containerStartupRetryBackoffMax = Objects.requireNonNull(max, "max must not be null");
            return this;
        }

        /**
         * Sets the port probe timeout.
         *
         * @param timeout the port probe timeout; must be positive
         * @return this builder
         */
        public Builder portProbeTimeout(Duration timeout) {
            this.portProbeTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Sets the HTTP probe timeout.
         *
         * @param timeout the HTTP probe timeout; must be positive
         * @return this builder
         */
        public Builder httpProbeTimeout(Duration timeout) {
            this.httpProbeTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Sets the put-archive pipe buffer size in bytes.
         *
         * @param bytes the put-archive pipe buffer size in bytes; must be positive
         * @return this builder
         */
        public Builder containerPutArchivePipeBufferBytes(int bytes) {
            this.containerPutArchivePipeBufferBytes = bytes;
            return this;
        }

        /**
         * Builds an immutable {@link AltcontainersConfiguration}.
         *
         * @return a new configuration
         */
        public AltcontainersConfiguration build() {
            return new AltcontainersConfiguration(
                    reaperDisabled,
                    reaperConnectionTimeout,
                    reaperStartupTimeout,
                    reaperStopTimeout,
                    containerStartupTimeout,
                    containerReadinessPollInitial,
                    containerReadinessPollMax,
                    containerStartupRetryBackoffMultiplier,
                    containerStartupRetryBackoffMax,
                    portProbeTimeout,
                    httpProbeTimeout,
                    containerPutArchivePipeBufferBytes);
        }
    }
}
