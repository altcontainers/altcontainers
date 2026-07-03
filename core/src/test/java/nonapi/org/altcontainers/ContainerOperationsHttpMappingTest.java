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

import static nonapi.org.altcontainers.ContainerOperations.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import nonapi.org.altcontainers.docker.DockerEndpoint;
import nonapi.org.altcontainers.docker.DockerHttpClient;
import okhttp3.OkHttpClient;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContainerOperationsHttpMappingTest {

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
    void awaitPortMappingsRejectsNonPositiveTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> awaitPortMappings(client, "id", List.of(8080), Duration.ZERO))
                .withMessageContaining("timeout must be positive");
    }

    @Test
    void containerExistsReturnsFalseOnNotFound() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThat(containerExists(client, "absent-id")).isFalse();
    }

    @Test
    void hostPortReturnsMinusOneForMissingBlankAndMalformedBindings() {
        // Missing port
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{}}}")
                .build());
        assertThat(hostPort(client, "id", 8080)).isEqualTo(-1);

        // Blank HostPort
        server.enqueue(new MockResponse.Builder().code(200).body("""
                        {"State":{"Status":"running"},"Config":{},
                        "NetworkSettings":{"Ports":{"8080/tcp":[{"HostPort":""}]}}}""").build());
        assertThat(hostPort(client, "id", 8080)).isEqualTo(-1);

        // Non-numeric HostPort
        server.enqueue(new MockResponse.Builder().code(200).body("""
                        {"State":{"Status":"running"},"Config":{},
                        "NetworkSettings":{"Ports":{"8080/tcp":[{"HostPort":"abc"}]}}}""").build());
        assertThat(hostPort(client, "id", 8080)).isEqualTo(-1);
    }

    @Test
    void inspectContainerDiagnosticsFormatsTerminalState() {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                        {"State":{"Status":"exited","OOMKilled":true,"Dead":true,"ExitCode":137,"Error":"boom"},
                        "Config":{},"NetworkSettings":{"Ports":{}}}""").build());

        String diag = inspectContainerDiagnostics(client, "id");
        assertThat(diag).contains("OOMKilled");
        assertThat(diag).contains("dead");
        assertThat(diag).contains("exitCode=137");
        assertThat(diag).contains("error=boom");
    }

    @Test
    void inspectContainerDiagnosticsReturnsEmptyOnFailure() {
        server.enqueue(new MockResponse.Builder().code(500).build());

        assertThat(inspectContainerDiagnostics(client, "id")).isEmpty();
    }

    // === Null/blank argument rejection tests ===

    @Test
    void hostPortRejectsNullId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hostPort(client, null, 80))
                .withMessageContaining("id must not be blank");
    }

    @Test
    void hostPortRejectsBlankId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hostPort(client, "", 80))
                .withMessageContaining("id must not be blank");
    }

    @Test
    void isContainerRunningRejectsNullId() {
        assertThatIllegalArgumentException().isThrownBy(() -> isContainerRunning(client, null));
    }

    @Test
    void isContainerRunningRejectsBlankId() {
        assertThatIllegalArgumentException().isThrownBy(() -> isContainerRunning(client, ""));
    }

    @Test
    void containerExistsRejectsNullId() {
        assertThatIllegalArgumentException().isThrownBy(() -> containerExists(client, null));
    }

    @Test
    void containerExistsRejectsBlankId() {
        assertThatIllegalArgumentException().isThrownBy(() -> containerExists(client, ""));
    }

    @Test
    void createContainerRejectsNullSpec() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createContainer(client, null))
                .withMessage("spec must not be null");
    }

    @Test
    void awaitPortMappingsIssuesSingleInspectCallForMultiplePorts() {
        for (int i = 0; i < 20; i++) {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body(
                            "{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{\"8080/tcp\":[{\"HostPort\":\"18080\"}],\"9090/tcp\":[{\"HostPort\":\"19090\"}]}}}")
                    .build());
        }

        awaitPortMappings(client, "test-id", List.of(8080, 9090), Duration.ofSeconds(2));

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void awaitPortMappingsTimesOutWhenOnePortMissing() {
        // Enqueue multiple responses with only 8080 bound (9090 missing)
        // so the poll retries and eventually times out.
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body(
                            "{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{\"8080/tcp\":[{\"HostPort\":\"18080\"}]}}}")
                    .build());
        }

        org.junit.jupiter.api.Assertions.assertThrows(
                ContainerException.class,
                () -> awaitPortMappings(client, "test-id", List.of(8080, 9090), Duration.ofMillis(500)));
    }

    @Test
    void awaitNetworkReadyObservesNetworkReadyDuringFinalSleep() {
        // A MockWebServer dispatcher that returns 404 for the first five calls
        // and 200 from the sixth. With NETWORK_READY_TIMEOUT of 2 s and
        // PollBackoff starting at 200 ms, the backoff exhausts the deadline after
        // ~4-5 iterations. Pre-fix, the loop breaks without re-checking. Post-fix,
        // the re-check calls inspectNetwork a sixth time, which succeeds.
        AtomicInteger callCount = new AtomicInteger(0);
        server.setDispatcher(new mockwebserver3.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int count = callCount.incrementAndGet();
                if (count <= 5) {
                    return new MockResponse.Builder().code(404).build();
                }
                return new MockResponse.Builder()
                        .code(200)
                        .body("{\"Labels\":{}}")
                        .build();
            }
        });

        NetworkOperations.awaitNetworkReady(client, "net-123", "test-net");

        // The sixth call (re-check) must have happened and returned successfully.
        assertThat(callCount.get()).isEqualTo(6);
    }

    @Test
    void awaitPortMappingsClearsInterruptFlagOnTimeout() {
        // Verify that when awaitPortMappings times out normally, the interrupt
        // flag is not left set. Enqueue not-ready responses and use a short
        // timeout to trigger the timeout (break) path naturally.
        String notReady = "{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{}}}";
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse.Builder().code(200).body(notReady).build());
        }

        // Clear any pre-existing interrupt flag.
        Thread.interrupted();
        try {
            awaitPortMappings(client, "id", List.of(8080), Duration.ofMillis(500));
        } catch (ContainerException expected) {
            // Expected: timeout
        }

        // The interrupt flag must still be false; if the break path left it set,
        // the caller would observe a stale interrupt.
        assertThat(Thread.currentThread().isInterrupted())
                .as("interrupt flag should not be set after normal timeout")
                .isFalse();
    }

    @Test
    void awaitPortMappingsObservesMappingReadyDuringFinalSleep() {
        // Enqueue three "not-ready" responses (only 8080 bound) followed by one "ready"
        // response (both ports bound). With a 500 ms timeout and PollBackoff starting at
        // 200 ms → 400 ms → ..., the third sleepWithBackoff call returns false because the
        // deadline has expired. Pre-fix, the loop breaks immediately (timeout). Post-fix,
        // the ready response is consumed by the re-check, and the method returns normally.
        String notReady =
                "{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{\"8080/tcp\":[{\"HostPort\":\"18080\"}]}}}";
        String ready =
                "{\"State\":{\"Status\":\"running\"},\"Config\":{},\"NetworkSettings\":{\"Ports\":{\"8080/tcp\":[{\"HostPort\":\"18080\"}],\"9090/tcp\":[{\"HostPort\":\"19090\"}]}}}";
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse.Builder().code(200).body(notReady).build());
        }
        server.enqueue(new MockResponse.Builder().code(200).body(ready).build());

        awaitPortMappings(client, "test-id", List.of(8080, 9090), Duration.ofMillis(500));

        // All four responses must be consumed (the last one by the re-check).
        assertThat(server.getRequestCount()).isEqualTo(4);
    }
}
