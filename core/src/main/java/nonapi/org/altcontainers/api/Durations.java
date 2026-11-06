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

/**
 * Shared duration formatting for readiness and startup diagnostics.
 *
 * <p>This class resides in the {@code nonapi} package because it is an
 * implementation detail shared by the public wait strategies.
 */
public final class Durations {

    private Durations() {
        // Intentionally empty
    }

    /**
     * Formats a duration for diagnostic messages using whole seconds when the
     * value is an exact multiple of one second, and milliseconds otherwise.
     *
     * @param timeout the duration to format
     * @return the formatted duration, for example {@code "60s"} or {@code "250ms"}
     */
    public static String format(Duration timeout) {
        long timeoutMillis = timeout.toMillis();
        return (timeoutMillis >= 1000 && timeoutMillis % 1000 == 0) ? timeout.toSeconds() + "s" : timeoutMillis + "ms";
    }
}
