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

package nonapi.org.altcontainers.api;

import java.time.Duration;
import org.altcontainers.api.ContainerException;

/**
 * Client-side Reaper configuration.
 *
 * @param disabled whether the reaper is disabled
 * @param reaperConnectionTimeout the connection/handshake timeout
 * @param reaperStartupTimeout the reaper startup timeout
 * @param reaperStopTimeout the reaper stop timeout
 */
public record Configuration(
        boolean disabled, Duration reaperConnectionTimeout, Duration reaperStartupTimeout, Duration reaperStopTimeout) {

    private static final String PREFIX = "altcontainers.reaper.";

    /**
     * Compact canonical constructor that validates fields.
     *
     * @param disabled whether the reaper is disabled
     * @param reaperConnectionTimeout the connection/handshake timeout
     * @param reaperStartupTimeout the reaper startup timeout
     * @param reaperStopTimeout the reaper stop timeout
     */
    public Configuration {
        if (reaperConnectionTimeout == null
                || reaperConnectionTimeout.isZero()
                || reaperConnectionTimeout.isNegative()) {
            throw new ContainerException(PREFIX + "connection.timeout.ms must be positive");
        }
        if (reaperStartupTimeout == null || reaperStartupTimeout.isZero() || reaperStartupTimeout.isNegative()) {
            throw new ContainerException(PREFIX + "startup.timeout.ms must be positive");
        }
        if (reaperStopTimeout == null || reaperStopTimeout.isZero() || reaperStopTimeout.isNegative()) {
            throw new ContainerException(PREFIX + "stop.timeout.ms must be positive");
        }
    }

    /**
     * Loads configuration from {@link AltcontainersProperties}, which applies
     * programmatic configuration, environment variables, properties files, and
     * defaults in precedence order.
     *
     * @return the loaded configuration
     */
    public static Configuration load() {
        AltcontainersProperties properties = AltcontainersProperties.instance();
        return new Configuration(
                properties.reaperDisabled(),
                properties.reaperConnectionTimeout(),
                properties.reaperStartupTimeout(),
                properties.reaperStopTimeout());
    }
}
