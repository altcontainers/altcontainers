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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DockerRequestException")
class DockerRequestExceptionTest {

    @Test
    @DisplayName("message-only constructor sets message and zero/null fields")
    void messageOnlyConstructor() {
        var ex = new DockerRequestException("test message");

        assertThat(ex.getMessage()).isEqualTo("test message");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.method()).isNull();
        assertThat(ex.path()).isNull();
        assertThat(ex.statusCode()).isZero();
        assertThat(ex.responseSnippet()).isNull();
    }

    @Test
    @DisplayName("message+cause constructor preserves both message and cause")
    void messageWithCauseConstructor() {
        var cause = new RuntimeException("root cause");
        var ex = new DockerRequestException("test message", cause);

        assertThat(ex.getMessage()).isEqualTo("test message");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.method()).isNull();
        assertThat(ex.path()).isNull();
        assertThat(ex.statusCode()).isZero();
        assertThat(ex.responseSnippet()).isNull();
    }

    @Nested
    @DisplayName("HTTP detail constructor")
    class HttpDetailConstructor {

        @Test
        @DisplayName("formats message with method, path, status, and response snippet")
        void formatsMessageWithSnippet() {
            var ex = new DockerRequestException("POST", "/containers/create", 409, "conflict details");

            assertThat(ex.getMessage()).isEqualTo("POST /containers/create returned 409: conflict details");
            assertThat(ex.method()).isEqualTo("POST");
            assertThat(ex.path()).isEqualTo("/containers/create");
            assertThat(ex.statusCode()).isEqualTo(409);
            assertThat(ex.responseSnippet()).isEqualTo("conflict details");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("omits colon and snippet when responseSnippet is null")
        void omitsSnippetWhenNull() {
            var ex = new DockerRequestException("GET", "/_ping", 500, null);

            assertThat(ex.getMessage()).isEqualTo("GET /_ping returned 500");
            assertThat(ex.method()).isEqualTo("GET");
            assertThat(ex.path()).isEqualTo("/_ping");
            assertThat(ex.statusCode()).isEqualTo(500);
            assertThat(ex.responseSnippet()).isNull();
        }

        @Test
        @DisplayName("omits colon and snippet when responseSnippet is empty")
        void omitsSnippetWhenEmpty() {
            var ex = new DockerRequestException("DELETE", "/containers/abc", 404, "");

            assertThat(ex.getMessage()).isEqualTo("DELETE /containers/abc returned 404");
            assertThat(ex.method()).isEqualTo("DELETE");
            assertThat(ex.path()).isEqualTo("/containers/abc");
            assertThat(ex.statusCode()).isEqualTo(404);
            assertThat(ex.responseSnippet()).isEmpty();
        }
    }
}
