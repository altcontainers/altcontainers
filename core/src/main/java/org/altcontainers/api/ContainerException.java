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
 * Thrown when a container lifecycle operation fails.
 *
 * <p>{@code ContainerException} is a {@link RuntimeException} that wraps failures from container
 * creation, startup, readiness waiting, destruction, network operations, and Docker daemon
 * communication errors.
 */
public class ContainerException extends RuntimeException {

    /**
     * Creates an exception with the given detail message.
     *
     * @param message the detail message
     */
    public ContainerException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public ContainerException(String message, Throwable cause) {
        super(message, cause);
    }
}
