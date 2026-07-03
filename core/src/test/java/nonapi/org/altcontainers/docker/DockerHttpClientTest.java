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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import nonapi.org.altcontainers.ContainerCreateSpec;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DockerHttpClientTest {

    private MockWebServer server;
    private DockerHttpClient client;
    private Gson gson;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        gson = new GsonBuilder().create();
        DockerEndpoint endpoint = DockerEndpoint.forHttpBaseUrl(server.url("/"));
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
        client = new DockerHttpClient(endpoint, httpClient, httpClient, gson);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void pingCallsPingEndpoint() throws Exception {
        server.enqueue(new MockResponse.Builder().body("OK").code(200).build());

        client.ping();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .isEqualTo("/_ping");
    }

    @Test
    void inspectImageEncodesImageReferencePath() throws Exception {
        server.enqueue(new MockResponse.Builder().body("{}").code(200).build());

        client.inspectImage("library/nginx:1.27");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .startsWith("/images/library/nginx%3A1.27/json");
    }

    @Test
    void pullImageConsumesSuccessfulProgressStream() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .body("{\"status\":\"Pulling from library/alpine\"}\n{\"status\":\"Digest: sha256:abc\"}\n")
                .code(200)
                .build());

        client.pullImage("alpine:latest");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .startsWith("/images/create?fromImage=alpine&tag=latest");
    }

    @Test
    void pullImageThrowsWhenProgressContainsError() {
        server.enqueue(new MockResponse.Builder()
                .body("{\"error\":\"pull denied\"}\n")
                .code(200)
                .build());

        assertThatThrownBy(() -> client.pullImage("alpine:latest"))
                .isInstanceOf(DockerRequestException.class)
                .hasMessageContaining("pull denied");
    }

    @Test
    void createContainerPostsSerializedRequest() throws Exception {
        server.enqueue(
                new MockResponse.Builder().body("{\"Id\":\"abc123\"}").code(201).build());

        ContainerCreateSpec spec = new ContainerCreateSpec(
                "alpine:latest",
                List.of("echo"),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                Map.of());

        String id = client.createContainer(spec);
        assertThat(id).isEqualTo("abc123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .isEqualTo("/containers/create");
    }

    @Test
    void createContainerRequiresIdInResponse() {
        server.enqueue(
                new MockResponse.Builder().body("{\"noId\":true}").code(201).build());

        ContainerCreateSpec spec = new ContainerCreateSpec(
                "alpine:latest",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                Map.of());

        assertThatThrownBy(() -> client.createContainer(spec))
                .isInstanceOf(DockerRequestException.class)
                .hasMessageContaining("Missing required field 'Id'");
    }

    @Test
    void startContainerPostsStartEndpoint() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.startContainer("abc");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .isEqualTo("/containers/abc/start");
    }

    @Test
    void stopContainerPostsTimeoutEndpoint() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.stopContainer("abc", 2);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .isEqualTo("/containers/abc/stop?t=2");
    }

    @Test
    void removeContainerUsesForceQuery() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.removeContainer("abc", true);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .isEqualTo("/containers/abc?force=1");
    }

    @Test
    void createNetworkPostsBridgeDriverAndLabels() throws Exception {
        server.enqueue(
                new MockResponse.Builder().body("{\"Id\":\"net123\"}").code(201).build());

        String id = client.createNetwork("test-net", Map.of("key", "val"));
        assertThat(id).isEqualTo("net123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .isEqualTo("/networks/create");
    }

    @Test
    void inspectContainerParsesResponse() throws Exception {
        server.enqueue(new MockResponse.Builder().body("""
                        {"State":{"Status":"running","Running":true,"OOMKilled":false,"Dead":false,"ExitCode":0},
                        "Config":{"Labels":{"managed":"true"}},
                        "NetworkSettings":{"Ports":{}}}""").code(200).build());

        DockerContainerInspect inspect = client.inspectContainer("abc");

        assertThat(inspect.running()).isTrue();
        assertThat(inspect.labels()).containsEntry("managed", "true");
    }

    @Test
    void inspectNetworkParsesResponse() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .body("{\"Labels\":{\"managed\":\"true\"}}")
                .code(200)
                .build());

        DockerNetworkInspect inspect = client.inspectNetwork("net123");

        assertThat(inspect.labels()).containsEntry("managed", "true");
    }

    @Test
    void listContainerIdsByLabelsUsesFilters() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .body("[{\"Id\":\"abc123\",\"Names\":[\"/test\"],\"State\":\"running\"}]")
                .code(200)
                .build());

        List<String> ids = client.listContainerIdsByLabels(Map.of("managed", "true"));

        assertThat(ids).containsExactly("abc123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .startsWith("/containers/json?all=1&filters=");
    }

    @Test
    void listContainerIdsByLabelsThrowsWhenResponseHasNoBody() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        assertThatThrownBy(() -> client.listContainerIdsByLabels(Map.of("managed", "true")))
                .isInstanceOf(DockerRequestException.class)
                .hasMessageContaining("returned no body");
    }

    @Test
    void listNetworkIdsByLabelsUsesFilters() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .body("[{\"Id\":\"net123\",\"Name\":\"test\"}]")
                .code(200)
                .build());

        List<String> ids = client.listNetworkIdsByLabels(Map.of("managed", "true"));

        assertThat(ids).containsExactly("net123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()
                        + (request.getUrl().encodedQuery() != null
                                ? "?" + request.getUrl().encodedQuery()
                                : ""))
                .startsWith("/networks?filters=");
    }

    @Test
    void getContainerLogsReturnsDemultiplexedText() throws Exception {
        // Build a multiplexed log frame with stdout payload "hello\n"
        byte[] payload = "hello\n".getBytes();
        byte[] frame = new byte[8 + payload.length];
        frame[0] = 1; // stdout
        frame[4] = (byte) (payload.length >> 24);
        frame[5] = (byte) (payload.length >> 16);
        frame[6] = (byte) (payload.length >> 8);
        frame[7] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 8, payload.length);

        Buffer buffer = new Buffer();
        buffer.write(frame);

        server.enqueue(new MockResponse.Builder().body(buffer).code(200).build());

        String logs = client.getContainerLogs("abc");

        assertThat(logs).isEqualTo("hello\n");
    }

    @Test
    void notFoundResponseThrowsDockerNotFoundException() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> client.ping()).isInstanceOf(DockerNotFoundException.class);
    }

    @Test
    void nonSuccessResponseIncludesMethodPathStatusAndBodySnippet() {
        server.enqueue(new MockResponse.Builder()
                .body("Internal error details")
                .code(500)
                .build());

        assertThatThrownBy(() -> client.ping())
                .isInstanceOf(DockerRequestException.class)
                .hasMessageContaining("GET")
                .hasMessageContaining("/_ping")
                .hasMessageContaining("500")
                .hasMessageContaining("Internal error details");
    }

    // === Null/blank argument rejection tests ===

    @Test
    void rejectsNullImageForInspectImage() {
        assertThatThrownBy(() -> client.inspectImage(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must not be blank");
    }

    @Test
    void rejectsBlankImageForInspectImage() {
        assertThatThrownBy(() -> client.inspectImage(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must not be blank");
    }

    @Test
    void rejectsNullImageForPullImage() {
        assertThatThrownBy(() -> client.pullImage(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must not be blank");
    }

    @Test
    void rejectsBlankImageForPullImage() {
        assertThatThrownBy(() -> client.pullImage(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must not be blank");
    }

    @Test
    void rejectsNullSpecForCreateContainer() {
        assertThatThrownBy(() -> client.createContainer(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullIdForStartContainer() {
        assertThatThrownBy(() -> client.startContainer(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForStartContainer() {
        assertThatThrownBy(() -> client.startContainer("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullIdForInspectContainer() {
        assertThatThrownBy(() -> client.inspectContainer(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForInspectContainer() {
        assertThatThrownBy(() -> client.inspectContainer("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullIdForStopContainer() {
        assertThatThrownBy(() -> client.stopContainer(null, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForStopContainer() {
        assertThatThrownBy(() -> client.stopContainer("", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullIdForRemoveContainer() {
        assertThatThrownBy(() -> client.removeContainer(null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForRemoveContainer() {
        assertThatThrownBy(() -> client.removeContainer("", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullNameForCreateNetwork() {
        assertThatThrownBy(() -> client.createNetwork(null, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankNameForCreateNetwork() {
        assertThatThrownBy(() -> client.createNetwork("", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullLabelsForCreateNetwork() {
        assertThatThrownBy(() -> client.createNetwork("name", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullIdForInspectNetwork() {
        assertThatThrownBy(() -> client.inspectNetwork(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForInspectNetwork() {
        assertThatThrownBy(() -> client.inspectNetwork("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullIdForRemoveNetwork() {
        assertThatThrownBy(() -> client.removeNetwork(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForRemoveNetwork() {
        assertThatThrownBy(() -> client.removeNetwork("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullIdForTailLogs() {
        assertThatThrownBy(() -> client.tailLogs(null, bytes -> {})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForTailLogs() {
        assertThatThrownBy(() -> client.tailLogs("", bytes -> {})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullConsumerForTailLogs() {
        assertThatThrownBy(() -> client.tailLogs("id", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullIdForGetContainerLogs() {
        assertThatThrownBy(() -> client.getContainerLogs(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdForGetContainerLogs() {
        assertThatThrownBy(() -> client.getContainerLogs("")).isInstanceOf(IllegalArgumentException.class);
    }

    // === putArchive null/blank argument rejection tests ===

    @Test
    void putArchiveRejectsNullContainerPath() {
        server.enqueue(new MockResponse.Builder().code(200).build());
        assertThatThrownBy(() -> client.putArchive("test-id", null, "file.txt", new byte[0], 0644))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("containerPath must not be blank");
    }

    @Test
    void putArchiveRejectsBlankContainerPath() {
        server.enqueue(new MockResponse.Builder().code(200).build());
        assertThatThrownBy(() -> client.putArchive("test-id", "", "file.txt", new byte[0], 0644))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("containerPath must not be blank");
    }

    @Test
    void putArchiveRejectsNullFileName() {
        server.enqueue(new MockResponse.Builder().code(200).build());
        assertThatThrownBy(() -> client.putArchive("test-id", "/tmp", null, new byte[0], 0644))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName must not be blank");
    }

    @Test
    void putArchiveRejectsBlankFileName() {
        server.enqueue(new MockResponse.Builder().code(200).build());
        assertThatThrownBy(() -> client.putArchive("test-id", "/tmp", "", new byte[0], 0644))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName must not be blank");
    }

    @Test
    void putArchiveRejectsNullContent() {
        server.enqueue(new MockResponse.Builder().code(200).build());
        assertThatThrownBy(() -> client.putArchive("test-id", "/tmp", "file.txt", null, 0644))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content must not be null");
    }

    @Test
    void tailLogsClosesResponseOnNonSuccess() throws Exception {
        MockWebServer testServer = new MockWebServer();
        testServer.start();
        try {
            testServer.enqueue(new MockResponse.Builder().code(404).build());

            OkHttpClient singleClient = new OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            DockerEndpoint endpoint = DockerEndpoint.forHttpBaseUrl(testServer.url("/"));
            DockerHttpClient testClient =
                    new DockerHttpClient(endpoint, singleClient, singleClient, new GsonBuilder().create());

            assertThatThrownBy(() -> testClient.tailLogs("test-id", bytes -> {}))
                    .isInstanceOf(DockerNotFoundException.class);

            testServer.enqueue(new MockResponse.Builder().code(200).body("{}").build());
            testClient.inspectImage("alpine");
        } finally {
            testServer.close();
        }
    }

    @Test
    void parseResponseBodyWrapsJsonSyntaxException() {
        server.enqueue(new MockResponse.Builder().body("not json").code(200).build());

        assertThatThrownBy(() -> client.inspectContainer("abc"))
                .isInstanceOf(DockerRequestException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void parseResponseArrayWrapsJsonSyntaxException() {
        server.enqueue(new MockResponse.Builder().body("not an array").code(200).build());

        assertThatThrownBy(() -> client.listContainerIdsByLabels(Map.of("a", "b")))
                .isInstanceOf(DockerRequestException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void pathSegmentEncodingHandlesNonBmpUnicodeCharacters() throws Exception {
        String containerId = "abc\uD83D\uDE00";
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{}}}")
                .build());

        client.inspectContainer(containerId);

        RecordedRequest request = server.takeRequest();
        String path = request.getUrl().encodedPath();
        assertThat(path).contains("%F0%9F%98%80");
        assertThat(path).doesNotContain("%ED%A0%BD");
    }

    @Test
    void shouldContinueProcessingPayloadsAfterConsumerThrows() throws Exception {
        MockWebServer testServer = new MockWebServer();
        testServer.start();
        try {
            byte[] payload1 = "hello\n".getBytes();
            byte[] payload2 = "world\n".getBytes();
            Buffer buffer = new Buffer();
            buffer.write(buildLogFrame(payload1));
            buffer.write(buildLogFrame(payload2));

            testServer.enqueue(new MockResponse.Builder().body(buffer).code(200).build());

            OkHttpClient singleClient = new OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            DockerEndpoint endpoint = DockerEndpoint.forHttpBaseUrl(testServer.url("/"));
            DockerHttpClient testClient =
                    new DockerHttpClient(endpoint, singleClient, singleClient, new GsonBuilder().create());

            CountDownLatch latch = new CountDownLatch(2);
            List<String> received = new ArrayList<>();
            AtomicInteger callCount = new AtomicInteger(0);

            testClient.tailLogs("test-id", payload -> {
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    latch.countDown();
                    throw new RuntimeException("consumer fail");
                }
                received.add(new String(payload, StandardCharsets.UTF_8));
                latch.countDown();
            });

            boolean done = latch.await(5, TimeUnit.SECONDS);
            assertThat(done).isTrue();
            assertThat(received).contains("world\n");
        } finally {
            testServer.close();
        }
    }

    private static byte[] buildLogFrame(byte[] payload) {
        byte[] frame = new byte[8 + payload.length];
        frame[0] = 1; // stdout
        frame[4] = (byte) (payload.length >> 24);
        frame[5] = (byte) (payload.length >> 16);
        frame[6] = (byte) (payload.length >> 8);
        frame[7] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 8, payload.length);
        return frame;
    }
}
