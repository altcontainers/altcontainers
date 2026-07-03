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

package nonapi.org.altcontainers;

import static nonapi.org.altcontainers.LogOperations.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import nonapi.org.altcontainers.docker.DockerEndpoint;
import nonapi.org.altcontainers.docker.DockerHttpClient;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogOperationsHttpMappingTest {

    private MockWebServer server;
    private DockerClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        Gson gson = new GsonBuilder().create();
        DockerEndpoint endpoint = DockerEndpoint.forHttpBaseUrl(server.url("/"));
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
        DockerHttpClient engineClient = new DockerHttpClient(endpoint, httpClient, httpClient, gson);
        client = new DockerClient(engineClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void getContainerLogsReturnsEmptyOnFailure() {
        server.enqueue(new MockResponse.Builder().code(500).build());

        assertThat(getLogs(client, "id")).isEmpty();
    }

    @Test
    void getContainerLogsReturnsCombinedLogs() throws Exception {
        // Build multiplexed frames with stdout and stderr payloads
        byte[] stdoutFrame = buildFrame((byte) 1, "hello\n");
        byte[] stderrFrame = buildFrame((byte) 2, "error\n");
        Buffer buffer = new Buffer();
        buffer.write(stdoutFrame);
        buffer.write(stderrFrame);

        server.enqueue(new MockResponse.Builder().body(buffer).code(200).build());

        String logs = getLogs(client, "id");
        assertThat(logs).contains("hello");
        assertThat(logs).contains("error");
    }

    private static byte[] buildFrame(byte streamType, String payload) {
        byte[] payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = payloadBytes.length;
        byte[] frame = new byte[8 + len];
        frame[0] = streamType;
        frame[4] = (byte) (len >> 24);
        frame[5] = (byte) (len >> 16);
        frame[6] = (byte) (len >> 8);
        frame[7] = (byte) len;
        System.arraycopy(payloadBytes, 0, frame, 8, len);
        return frame;
    }
}
