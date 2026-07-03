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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import nonapi.org.altcontainers.ContainerCreateSpec;
import org.altcontainers.api.BindMount;
import org.altcontainers.api.Ulimit;
import org.junit.jupiter.api.Test;

class DockerJsonTest {

    private static final Gson GSON = new GsonBuilder().create();
    private static final DockerJson DOCKER_JSON = new DockerJson(GSON);

    @Test
    void parseImageReferenceWithExplicitTag() {
        ImageReference ref = ImageReference.parse("alpine:3.20");
        assertThat(ref.fromImage()).isEqualTo("alpine");
        assertThat(ref.tag()).isEqualTo("3.20");
    }

    @Test
    void parseImageReferenceWithoutTagUsesLatest() {
        ImageReference ref = ImageReference.parse("nginx");
        assertThat(ref.fromImage()).isEqualTo("nginx");
        assertThat(ref.tag()).isEqualTo("latest");
    }

    @Test
    void parseImageReferenceWithRegistryPortAndTag() {
        ImageReference ref = ImageReference.parse("localhost:5000/repo/image:tag");
        assertThat(ref.fromImage()).isEqualTo("localhost:5000/repo/image");
        assertThat(ref.tag()).isEqualTo("tag");
    }

    @Test
    void parseImageReferenceWithDigestHasNoTag() {
        ImageReference ref = ImageReference.parse("repo/image@sha256:abc");
        assertThat(ref.fromImage()).isEqualTo("repo/image@sha256:abc");
        assertThat(ref.tag()).isNull();
    }

    @Test
    void containerCreateRequestSerializesCompleteSpec() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "alpine:latest",
                List.of("sh", "-c", "echo hello"),
                List.of(8080),
                List.<BindMount>of(),
                "test-network",
                List.of("alias1"),
                "/app",
                List.<Ulimit>of(),
                100_000_000L,
                200_000_000L,
                64_000_000L,
                512,
                100_000L,
                50_000L,
                Map.of("key1", "value1"),
                Map.of("ENV_VAR", "env_value"),
                Map.of(8080, 9090));

        JsonObject json = DOCKER_JSON.containerCreateRequest(spec);

        assertThat(json.get("Image").getAsString()).isEqualTo("alpine:latest");
        assertThat(json.getAsJsonArray("Cmd").size()).isEqualTo(3);
        assertThat(json.getAsJsonObject("ExposedPorts").has("8080/tcp")).isTrue();
        assertThat(json.getAsJsonObject("Labels").get("key1").getAsString()).isEqualTo("value1");
        assertThat(json.getAsJsonArray("Env").get(0).getAsString()).isEqualTo("ENV_VAR=env_value");
        assertThat(json.get("WorkingDir").getAsString()).isEqualTo("/app");

        JsonObject hostConfig = json.getAsJsonObject("HostConfig");
        assertThat(hostConfig.get("Memory").getAsLong()).isEqualTo(100_000_000L);
        assertThat(hostConfig.get("MemorySwap").getAsLong()).isEqualTo(200_000_000L);
        assertThat(hostConfig.get("ShmSize").getAsLong()).isEqualTo(64_000_000L);
        assertThat(hostConfig.get("CpuShares").getAsInt()).isEqualTo(512);
        assertThat(hostConfig.get("CpuPeriod").getAsLong()).isEqualTo(100_000L);
        assertThat(hostConfig.get("CpuQuota").getAsLong()).isEqualTo(50_000L);
        assertThat(hostConfig.get("NetworkMode").getAsString()).isEqualTo("test-network");

        JsonObject networkingConfig = json.getAsJsonObject("NetworkingConfig");
        JsonObject endpointsConfig = networkingConfig.getAsJsonObject("EndpointsConfig");
        assertThat(endpointsConfig.has("test-network")).isTrue();
    }

    @Test
    void filtersJsonSerializesLabelAndSemantics() {
        Map<String, String> labels = Map.of("a", "1", "b", "2");
        String filtersJson = DOCKER_JSON.labelFiltersJson(labels);

        JsonObject filters = GSON.fromJson(filtersJson, JsonObject.class);
        assertThat(filters.getAsJsonArray("label").size()).isEqualTo(2);
        assertThat(filters.getAsJsonArray("label").get(0).getAsString()).isIn("a=1", "b=2");
    }

    @Test
    void extractContainerInspectFields() {
        String responseJson = """
                {
                    "State": {
                        "Status": "running",
                        "Running": true,
                        "OOMKilled": true,
                        "Dead": true,
                        "ExitCode": 137,
                        "Error": "oom"
                    },
                    "Config": {
                        "Labels": {
                            "managed": "true"
                        }
                    },
                    "NetworkSettings": {
                        "Ports": {
                            "8080/tcp": [
                                {"HostIp": "0.0.0.0", "HostPort": "32768"}
                            ]
                        }
                    }
                }""";

        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);
        DockerContainerInspect inspect = DOCKER_JSON.containerInspect(json);

        assertThat(inspect.running()).isTrue();
        assertThat(inspect.oomKilled()).isTrue();
        assertThat(inspect.dead()).isTrue();
        assertThat(inspect.exitCode()).isEqualTo(137);
        assertThat(inspect.error()).isEqualTo("oom");
        assertThat(inspect.labels()).containsEntry("managed", "true");
        assertThat(inspect.ports()).containsKey("8080/tcp");
    }

    @Test
    void missingOptionalInspectFieldsBecomeEmptyValues() {
        String responseJson = "{}";
        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);
        DockerContainerInspect inspect = DOCKER_JSON.containerInspect(json);

        assertThat(inspect.running()).isFalse();
        assertThat(inspect.labels()).isEmpty();
        assertThat(inspect.ports()).isEmpty();
    }

    @Test
    void extractNetworkLabels() {
        String responseJson = """
                {
                    "Labels": {
                        "managed": "true"
                    }
                }""";

        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);
        DockerNetworkInspect inspect = DOCKER_JSON.networkInspect(json);

        assertThat(inspect.labels()).containsEntry("managed", "true");
    }

    // === Null/blank argument rejection tests ===

    @Test
    void rejectsNullSpecForContainerCreateRequest() {
        assertThatThrownBy(() -> DOCKER_JSON.containerCreateRequest(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullNameForNetworkCreateRequest() {
        assertThatThrownBy(() -> DOCKER_JSON.networkCreateRequest(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankNameForNetworkCreateRequest() {
        assertThatThrownBy(() -> DOCKER_JSON.networkCreateRequest("", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullLabelsForNetworkCreateRequest() {
        assertThatThrownBy(() -> DOCKER_JSON.networkCreateRequest("name", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void extractContainerInspectUsesRunningBooleanNotStatusString() {
        // Docker may change the Status string casing; the canonical signal is the Running boolean.
        String responseJson = "{\"State\":{\"Status\":\"Running\",\"Running\":true}}";
        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);

        DockerContainerInspect inspect = DOCKER_JSON.containerInspect(json);

        assertThat(inspect.running()).isTrue();
    }

    @Test
    void extractContainerInspectHandlesNullExitCode() {
        // Docker may return "ExitCode": null for containers that never produced an exit code.
        String responseJson = "{\"State\":{\"ExitCode\":null,\"Status\":\"exited\",\"Running\":false}}";
        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);

        DockerContainerInspect inspect = DOCKER_JSON.containerInspect(json);

        assertThat(inspect.exitCode()).isNull();
    }

    @Test
    void hostPortReturnsNegativeOneForNonNumericPort() {
        String responseJson = """
                {
                    "NetworkSettings": {
                        "Ports": {
                            "8080/tcp": [
                                {"HostIp": "0.0.0.0", "HostPort": "badvalue"}
                            ]
                        }
                    }
                }""";
        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);
        DockerContainerInspect inspect = DOCKER_JSON.containerInspect(json);

        assertThat(inspect.hostPort(8080)).isEqualTo(-1);
    }

    @Test
    void extractContainerInspectParsesIntegerExitCode() {
        String responseJson = "{\"State\":{\"ExitCode\":137,\"Status\":\"exited\",\"Running\":false}}";
        JsonObject json = GSON.fromJson(responseJson, JsonObject.class);

        DockerContainerInspect inspect = DOCKER_JSON.containerInspect(json);

        assertThat(inspect.exitCode()).isEqualTo(137);
    }
}
