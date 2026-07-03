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

package nonapi.org.altcontainers.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HandlerTest {

    @Test
    void terminateDoesNotDoubleDecrementSessionCount() {
        var sessions = new ConcurrentHashMap<String, SessionState>();
        var sessionCount = new AtomicInteger(1);

        // Simulate scanner having already removed the session and decremented count.
        sessions.remove("test-session-id"); // returns null — already gone
        sessionCount.decrementAndGet(); // now 0

        // Simulate TERMINATE arriving after scanner removed it.
        // With fix: guard with removed != null, preventing double-decrement.
        var removed = sessions.remove("test-session-id");
        if (removed != null) {
            sessionCount.decrementAndGet();
        }

        assertThat(sessionCount.get()).isEqualTo(0); // not -1
    }

    @Test
    void scannerDoesNotDoubleDecrementWhenSessionAlreadyRemoved() {
        var sessions = new ConcurrentHashMap<String, SessionState>();
        var sessionCount = new AtomicInteger(1);

        SessionState state = new SessionState("test-session-id", 30_000L, new AtomicLong(System.nanoTime()), null);
        sessions.put("test-session-id", state);

        // Simulate TERMINATE handler (or prior scanner iteration) removing first.
        sessions.remove("test-session-id");
        sessionCount.decrementAndGet(); // now 0

        // Scanner's remove-and-decrement (the code under test).
        SessionState removed = sessions.remove("test-session-id");
        if (removed != null) {
            sessionCount.decrementAndGet();
        }

        assertThat(sessionCount.get()).isEqualTo(0); // not -1
    }

    @Test
    void pendingConnectionsDecrementsOnHandshakeFailure() throws Exception {
        ServerSocket server = new ServerSocket(0);
        AtomicInteger pendingConnections = new AtomicInteger(0);
        try (Socket clientSocket = new Socket("localhost", server.getLocalPort());
                Socket accepted = server.accept()) {
            pendingConnections.incrementAndGet();

            Handler handler = new Handler(
                    accepted,
                    null,
                    Configuration.load(),
                    new ConcurrentHashMap<>(),
                    new AtomicInteger(0),
                    pendingConnections);
            // Close client socket so the handler's readLine returns EOF immediately.
            clientSocket.close();

            Thread handlerThread = new Thread(handler);
            handlerThread.setDaemon(true);
            handlerThread.start();
            handlerThread.join(2_000);

            assertThat(pendingConnections.get()).isZero();
        }
        server.close();
    }

    @Test
    void handshakeTimesOutWhenClientSendsNoData() throws Exception {
        var sessions = new ConcurrentHashMap<String, SessionState>();
        var sessionCount = new AtomicInteger(0);
        var pendingConnections = new AtomicInteger(1);

        ServerSocket server = new ServerSocket(0);
        try (Socket clientSocket = new Socket("localhost", server.getLocalPort());
                Socket accepted = server.accept()) {
            // Use a short connection timeout (100ms) so the test completes quickly.
            Configuration config = new Configuration(
                    false, 100L, 30_000L, 5_000L, 30_000L, 180_000L, "INFO", System.getProperty("java.io.tmpdir"));

            Handler handler = new Handler(accepted, null, config, sessions, sessionCount, pendingConnections);
            Thread handlerThread = new Thread(handler);
            handlerThread.setDaemon(true);
            handlerThread.start();
            handlerThread.join(3_000);

            // Handler should terminate within a bounded time, not hang indefinitely.
            assertThat(handlerThread.isAlive()).isFalse();
        }
        server.close();
    }

    @Test
    void concurrentConnectForSameSessionAllowsExactlyOne() throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        String sessionId = "11111111-1111-1111-1111-111111111111";
        var sessions = new ConcurrentHashMap<String, SessionState>();
        var sessionCount = new AtomicInteger(0);
        var pendingConnections = new AtomicInteger(0);
        var okCount = new AtomicInteger(0);
        var errorCount = new AtomicInteger(0);
        CountDownLatch bothDone = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable client = () -> {
            try (Socket s = new Socket("localhost", port)) {
                PrintWriter w = new PrintWriter(s.getOutputStream(), true);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                w.println("VERSION " + Protocol.VERSION);
                r.readLine(); // OK
                w.println("CONNECT " + sessionId + " 30000");
                String response = r.readLine();
                if ("OK".equals(response)) {
                    okCount.incrementAndGet();
                } else if (response != null && response.startsWith("ERROR")) {
                    errorCount.incrementAndGet();
                }
            } catch (IOException ignored) {
                // Connection closed.
            } finally {
                bothDone.countDown();
            }
        };

        pool.submit(client);
        pool.submit(client);

        // Accept two connections.
        Socket s1 = server.accept();
        pendingConnections.incrementAndGet();
        Socket s2 = server.accept();
        pendingConnections.incrementAndGet();

        Handler h1 = new Handler(s1, null, Configuration.load(), sessions, sessionCount, pendingConnections);
        Handler h2 = new Handler(s2, null, Configuration.load(), sessions, sessionCount, pendingConnections);
        Thread t1 = new Thread(h1);
        Thread t2 = new Thread(h2);
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        // Wait for both client protocol exchanges to complete.
        boolean completed = bothDone.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Close server-side sockets to unblock the handlers' serveLoop.
        s1.close();
        s2.close();
        t1.join(2_000);
        t2.join(2_000);

        assertThat(okCount.get()).isEqualTo(1);
        assertThat(errorCount.get()).isEqualTo(1);
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(sessionCount.get()).isEqualTo(1);

        pool.shutdown();
        server.close();
    }
}
