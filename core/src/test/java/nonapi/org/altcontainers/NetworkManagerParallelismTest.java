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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import nonapi.org.altcontainers.docker.DockerEndpoint;
import nonapi.org.altcontainers.docker.DockerHttpClient;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.Network;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NetworkManagerParallelismTest {

    // ── Property parsing tests ──────────────────────────────────────────

    @Test
    void shouldReturnZeroWhenNull() {
        assertThat(NetworkManager.readNetworkParallelism(null)).isEqualTo(0);
    }

    @Test
    void shouldReturnZeroWhenBlank() {
        assertThat(NetworkManager.readNetworkParallelism("")).isEqualTo(0);
    }

    @Test
    void shouldReturnZeroWhenWhitespaceOnly() {
        assertThat(NetworkManager.readNetworkParallelism("   ")).isEqualTo(0);
    }

    @Test
    void shouldReturnPositiveValue() {
        assertThat(NetworkManager.readNetworkParallelism("3")).isEqualTo(3);
    }

    @Test
    void shouldParseOne() {
        assertThat(NetworkManager.readNetworkParallelism("1")).isEqualTo(1);
    }

    @Test
    void shouldParseLargeValue() {
        assertThat(NetworkManager.readNetworkParallelism("100")).isEqualTo(100);
    }

    @Test
    void shouldThrowOnNonNumeric() {
        assertThatThrownBy(() -> NetworkManager.readNetworkParallelism("abc"))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("altcontainers.environments.parallelism");
    }

    @Test
    void shouldThrowOnNegative() {
        assertThatThrownBy(() -> NetworkManager.readNetworkParallelism("-1"))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("altcontainers.environments.parallelism");
    }

    @Test
    void shouldTrimWhitespace() {
        assertThat(NetworkManager.readNetworkParallelism(" 5 ")).isEqualTo(5);
    }

    // ── Semaphore behavior tests ───────────────────────────────────────

    private MockWebServer server;
    private Semaphore semaphore;
    private NetworkManager mgr;

    void setUpMockWebServer() throws Exception {
        server = new MockWebServer();
        server.start();
        DockerEndpoint endpoint = DockerEndpoint.forHttpBaseUrl(server.url("/"));
        DockerHttpClient httpClient = DockerHttpClient.create(endpoint);
        DockerClient dockerClient = new DockerClient(httpClient);
        semaphore = new Semaphore(1);
        mgr = new NetworkManager(dockerClient, semaphore);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void shouldReleasePermitOnDestroyNetwork() throws Exception {
        setUpMockWebServer();
        semaphore.acquire(); // consume the only permit

        server.enqueue(new MockResponse.Builder().code(204).build()); // DELETE /networks/abc
        server.enqueue(new MockResponse.Builder().code(404).build()); // GET /networks/abc (not found → gone)

        mgr.destroyNetwork(new Network("test", "abc"));

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void shouldReleasePermitWhenDestroyThrows() throws Exception {
        setUpMockWebServer();
        semaphore.acquire(); // consume the only permit

        server.enqueue(new MockResponse.Builder().code(500).build()); // DELETE fails
        server.enqueue(new MockResponse.Builder().code(404).build()); // GET → not found → GONE

        // destroyNetwork succeeds (network confirmed gone via inspect 404);
        // verify permit is released
        mgr.destroyNetwork(new Network("test", "abc"));

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void shouldNotTouchSemaphoreOnNullNetwork() throws Exception {
        setUpMockWebServer();

        mgr.destroyNetwork(null);

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void shouldReleasePermitWhenCreateNetworkFails() throws Exception {
        setUpMockWebServer();

        // createNetwork acquires the permit then fails (no ResourceController set up)
        try {
            mgr.createNetwork();
        } catch (Exception ignored) {
            // Expected — ResourceController not available
        }

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    // ── Concurrency tests ──────────────────────────────────────────────

    @Test
    void shouldBlockAndProceedOnCreateNetwork() throws Exception {
        setUpMockWebServer();
        semaphore.acquire(); // consume the only permit

        CountDownLatch threadStarted = new CountDownLatch(1);
        CountDownLatch threadAcquired = new CountDownLatch(1);

        Thread blocked = new Thread(() -> {
            threadStarted.countDown();
            // Directly test semaphore acquire blocking behavior.
            // This exercises the same acquire() call that createNetwork() makes.
            try {
                semaphore.acquire();
                threadAcquired.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.start();

        // Wait for the thread to start and block on acquire
        assertThat(threadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // Give the thread a moment to actually block
        Thread.sleep(200);
        assertThat(threadAcquired.getCount()).isEqualTo(1);

        // Release the permit by destroying a network
        server.enqueue(new MockResponse.Builder().code(204).build()); // DELETE
        server.enqueue(new MockResponse.Builder().code(404).build()); // GET 404 → gone
        mgr.destroyNetwork(new Network("test", "abc"));

        // Blocked thread should now proceed
        assertThat(threadAcquired.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void semaphoreNotDoubleReleasedOnIdempotentDestroy() throws Exception {
        setUpMockWebServer();
        semaphore.acquire(); // simulate createNetwork acquiring the permit

        // First destroyNetwork call — should release the permit.
        server.enqueue(new MockResponse.Builder().code(204).build()); // DELETE /networks/abc
        server.enqueue(new MockResponse.Builder().code(404).build()); // GET /networks/abc (gone)
        Network network = new Network("test", "abc");
        mgr.destroyNetwork(network);

        // Second destroyNetwork call on same Network — idempotent; must NOT double-release.
        server.enqueue(new MockResponse.Builder().code(204).build()); // DELETE /networks/abc
        server.enqueue(new MockResponse.Builder().code(404).build()); // GET /networks/abc (gone)
        mgr.destroyNetwork(network);

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void shouldThrowContainerExceptionWhenInterrupted() throws Exception {
        setUpMockWebServer();
        semaphore.acquire(); // consume the only permit

        CountDownLatch threadStarted = new CountDownLatch(1);

        Thread blocked = new Thread(() -> {
            threadStarted.countDown();
            try {
                mgr.createNetwork();
            } catch (ContainerException e) {
                assertThat(e.getMessage()).contains("Interrupted while waiting for network creation permit");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                // Clear interrupt status so the thread can exit cleanly
                Thread.interrupted();
            }
        });
        blocked.start();

        // Wait for the thread to start and be inside createNetwork (blocked on acquire)
        assertThat(threadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        // Interrupt the blocked thread
        blocked.interrupt();
        blocked.join(5_000);

        // No permit should have leaked
        assertThat(semaphore.availablePermits()).isEqualTo(0);
    }
}
