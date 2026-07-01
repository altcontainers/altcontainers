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
 * HTTP protocol variant for container readiness probes.
 */
public enum Protocol {

    /**
     * Hypertext Transfer Protocol (unencrypted)
     */
    HTTP("http"),

    /**
     * Hypertext Transfer Protocol Secure (encrypted)
     */
    HTTPS("https");

    private final String scheme;

    Protocol(String scheme) {
        this.scheme = scheme;
    }

    /**
     * Returns the URI scheme for this protocol.
     *
     * @return the URI scheme (e.g., "http" or "https")
     */
    String scheme() {
        return scheme;
    }
}
