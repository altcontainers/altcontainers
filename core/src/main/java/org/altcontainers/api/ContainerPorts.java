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

/**
 * The valid TCP container port range, shared by container specs and wait
 * strategies so the constraint has a single definition.
 */
final class ContainerPorts {

    /** Lowest valid container port. */
    static final int MIN = 1;

    /** Highest valid container port. */
    static final int MAX = 65535;

    private ContainerPorts() {
        // Intentionally empty
    }

    /**
     * Returns whether the given value is a valid container port.
     *
     * @param port the value to check
     * @return {@code true} if {@code port} is within the valid range
     */
    static boolean isValid(int port) {
        return port >= MIN && port <= MAX;
    }

    /**
     * Returns the valid range rendered for error messages.
     *
     * @return the range, for example {@code "1..65535"}
     */
    static String range() {
        return MIN + ".." + MAX;
    }
}
