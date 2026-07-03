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

package nonapi.org.altcontainers.docker;

/**
 * Internal runtime exception for Docker API non-success responses.
 *
 * <p>Carries method, path, status code, and a short response-body snippet for diagnostics.
 */
public final class DockerRequestException extends RuntimeException {

    private final String method;
    private final String path;
    private final int statusCode;
    private final String responseSnippet;

    /**
     * Creates an exception with a simple message.
     *
     * @param message the detail message
     */
    public DockerRequestException(String message) {
        super(message);
        this.method = null;
        this.path = null;
        this.statusCode = 0;
        this.responseSnippet = null;
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public DockerRequestException(String message, Throwable cause) {
        super(message, cause);
        this.method = null;
        this.path = null;
        this.statusCode = 0;
        this.responseSnippet = null;
    }

    /**
     * Creates an exception carrying HTTP details.
     *
     * @param method the HTTP method
     * @param path the request path
     * @param statusCode the HTTP status code
     * @param responseSnippet a short snippet of the response body
     */
    public DockerRequestException(String method, String path, int statusCode, String responseSnippet) {
        super(method + " " + path + " returned " + statusCode
                + (responseSnippet != null && !responseSnippet.isEmpty() ? ": " + responseSnippet : ""));
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
        this.responseSnippet = responseSnippet;
    }

    /**
     * Returns the HTTP method that caused this error, or {@code null}.
     *
     * @return the HTTP method
     */
    public String method() {
        return method;
    }

    /**
     * Returns the request path that caused this error, or {@code null}.
     *
     * @return the request path
     */
    public String path() {
        return path;
    }

    /**
     * Returns the HTTP status code, or 0.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the response body snippet, or {@code null}.
     *
     * @return the response snippet
     */
    public String responseSnippet() {
        return responseSnippet;
    }
}
