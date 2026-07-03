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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerEndpointTest {

    @Test
    void systemPropertyHostOverridesDockerHost() {
        DockerEndpoint endpoint = DockerEndpoint.resolve("tcp://property.example:2375", "tcp://env.example:2375");

        assertThat(endpoint.configuredHost()).isEqualTo("tcp://property.example:2375");
        assertThat(endpoint.scheme()).isEqualTo(DockerEndpoint.Scheme.HTTP);
        assertThat(endpoint.baseUrlString()).isEqualTo("http://property.example:2375/");
    }

    @Test
    void dockerHostOverridesDefaultWhenSystemPropertyBlank() {
        DockerEndpoint endpoint = DockerEndpoint.resolve(" ", "tcp://env.example:2375");

        assertThat(endpoint.configuredHost()).isEqualTo("tcp://env.example:2375");
    }

    @Test
    void blankConfigurationUsesDefaultUnixSocket() {
        DockerEndpoint endpoint = DockerEndpoint.parse(DockerEndpoint.DEFAULT_DOCKER_HOST);

        assertThat(endpoint.configuredHost()).isEqualTo("unix:///var/run/docker.sock");
        assertThat(endpoint.scheme()).isEqualTo(DockerEndpoint.Scheme.UNIX);
        assertThat(endpoint.baseUrlString()).isEqualTo("http://docker/");
    }

    @Test
    void mapsTcpSchemeToHttpBaseUrl() {
        DockerEndpoint endpoint = DockerEndpoint.parse("tcp://localhost:2375");

        assertThat(endpoint.baseUrlString()).isEqualTo("http://localhost:2375/");
    }

    @Test
    void acceptsHttpAndHttpsBaseUrls() {
        DockerEndpoint httpEndpoint = DockerEndpoint.parse("http://localhost:2375");
        assertThat(httpEndpoint.baseUrlString()).isEqualTo("http://localhost:2375/");
        assertThat(httpEndpoint.scheme()).isEqualTo(DockerEndpoint.Scheme.HTTP);

        DockerEndpoint httpsEndpoint = DockerEndpoint.parse("https://docker.example:2376");
        assertThat(httpsEndpoint.baseUrlString()).isEqualTo("https://docker.example:2376/");
        assertThat(httpsEndpoint.scheme()).isEqualTo(DockerEndpoint.Scheme.HTTPS);
    }

    @Test
    void httpEndpointWithTrailingSlashDoesNotDoubleSlash() {
        DockerEndpoint endpoint = DockerEndpoint.parse("http://localhost:2375/");

        String resolved = endpoint.baseUrl().resolve("_ping").toString();

        assertThat(resolved).isEqualTo("http://localhost:2375/_ping");
    }

    @Test
    void httpsEndpointWithTrailingSlashDoesNotDoubleSlash() {
        DockerEndpoint endpoint = DockerEndpoint.parse("https://secure-host:2376/");

        String resolved = endpoint.baseUrl().resolve("_ping").toString();

        assertThat(resolved).isEqualTo("https://secure-host:2376/_ping");
    }

    @Test
    void httpSubPathEndpointWithTrailingSlashDoesNotDoubleSlash() {
        DockerEndpoint endpoint = DockerEndpoint.parse("http://host:8080/api/v1/");

        String resolved = endpoint.baseUrl().resolve("_ping").toString();

        assertThat(resolved).isEqualTo("http://host:8080/api/v1/_ping");
    }

    @Test
    void httpEndpointWithoutTrailingSlashStillWorks() {
        DockerEndpoint endpoint = DockerEndpoint.parse("http://localhost:2375");

        assertThat(endpoint.baseUrlString()).isEqualTo("http://localhost:2375/");
        assertThat(endpoint.baseUrl().resolve("_ping").toString()).isEqualTo("http://localhost:2375/_ping");
    }

    @Test
    void httpSubPathEndpointWithoutTrailingSlashStillWorks() {
        DockerEndpoint endpoint = DockerEndpoint.parse("http://host:8080/api/v1");

        String resolved = endpoint.baseUrl().resolve("_ping").toString();

        assertThat(resolved).isEqualTo("http://host:8080/api/v1/_ping");
    }

    @Test
    void rejectsNamedPipeEndpoint() {
        assertThatThrownBy(() -> DockerEndpoint.parse("npipe:////./pipe/docker_engine"))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Docker named pipe endpoints are not supported");
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> DockerEndpoint.parse("ssh://docker.example"))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Unsupported Docker host scheme");
    }

    @Test
    void rejectsMalformedEndpoint() {
        assertThatThrownBy(() -> DockerEndpoint.parse(":::not-a-uri:::"))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Invalid Docker host");
    }

    @Test
    void validateRejectsMissingUnixSocketPath() {
        assertThatThrownBy(() -> DockerEndpoint.parse("unix:///tmp/definitely-missing-altcontainers.sock")
                        .validate())
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Docker Unix socket does not exist");
    }

    @Test
    void validateRejectsNonSocketUnixPath(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("regular-file.txt");
        Files.writeString(tempFile, "not a socket");

        DockerEndpoint endpoint = DockerEndpoint.parse("unix://" + tempFile);
        assertThatThrownBy(() -> endpoint.validate())
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Docker Unix socket path is not a socket");
    }

    // === Null argument rejection tests ===

    @Test
    void rejectsNullHostForParse() {
        assertThatNullPointerException().isThrownBy(() -> DockerEndpoint.parse(null));
    }

    @Test
    void rejectsNullBaseUrlForForHttpBaseUrl() {
        assertThatNullPointerException().isThrownBy(() -> DockerEndpoint.forHttpBaseUrl(null));
    }

    @Test
    void validateRejectsFifoPath() throws Exception {
        Path fifoPath = Path.of(System.getProperty("java.io.tmpdir"), "altcontainers-test-fifo-" + UUID.randomUUID());
        try {
            new ProcessBuilder("mkfifo", fifoPath.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (IOException e) {
            return; // mkfifo unavailable; skip test.
        }
        try {
            DockerEndpoint endpoint = DockerEndpoint.parse("unix://" + fifoPath);
            assertThatThrownBy(() -> endpoint.validate())
                    .isInstanceOf(ContainerException.class)
                    .hasMessageContaining("Docker Unix socket path is not a socket");
        } finally {
            Files.deleteIfExists(fifoPath);
        }
    }

    @Test
    void parsesIpv6TcpHost() {
        DockerEndpoint endpoint = DockerEndpoint.parse("tcp://[::1]:2375");

        assertThat(endpoint.baseUrlString()).isEqualTo("http://[::1]:2375/");
        assertThat(endpoint.baseUrl().resolve("_ping").toString()).isEqualTo("http://[::1]:2375/_ping");
    }

    @Test
    void parsesIpv6HttpHostWithSubPath() {
        DockerEndpoint endpoint = DockerEndpoint.parse("http://[::1]:8080/api/v1");

        assertThat(endpoint.baseUrl().resolve("_ping").toString()).isEqualTo("http://[::1]:8080/api/v1/_ping");
    }

    @Test
    void validateRejectsDevicePath() {
        Path devNull = Path.of("/dev/null");
        if (!java.nio.file.Files.exists(devNull)) {
            return; // not a Unix/Linux host
        }
        DockerEndpoint endpoint = DockerEndpoint.parse("unix:///dev/null");

        assertThatThrownBy(() -> endpoint.validate())
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Docker Unix socket path is not a socket");
    }
}
