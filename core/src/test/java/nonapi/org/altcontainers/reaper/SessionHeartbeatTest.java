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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionHeartbeatTest {

    @Test
    void stopCausesRunToExitWithoutInterrupt() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter mockWriter = new PrintWriter(baos, true);
        AtomicBoolean disconnectCalled = new AtomicBoolean(false);

        SessionHeartbeat heartbeat = new SessionHeartbeat(
                mockWriter,
                null,
                10L, // 10ms interval for fast test
                () -> disconnectCalled.set(true));

        heartbeat.stop(); // set running = false before starting
        Thread t = new Thread(heartbeat);
        t.start();
        try {
            t.join(500); // should exit almost immediately
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(t.isAlive()).isFalse();
        assertThat(disconnectCalled.get()).isFalse(); // stop is not a disconnect
    }

    @Test
    void interruptCausesRunToExit() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter mockWriter = new PrintWriter(baos, true);
        AtomicBoolean disconnectCalled = new AtomicBoolean(false);

        SessionHeartbeat heartbeat = new SessionHeartbeat(
                mockWriter,
                null,
                60_000L, // long interval — relies on interrupt to exit
                () -> disconnectCalled.set(true));

        Thread t = new Thread(heartbeat);
        t.start();
        // Give the thread time to enter Thread.sleep().
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        t.interrupt();
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(t.isAlive()).isFalse();
        assertThat(disconnectCalled.get()).isFalse(); // interrupt is not a disconnect
    }

    @Test
    void runDetectsWriteFailureViaCheckError() throws Exception {
        // PrintWriter silently swallows IOExceptions; checkError() is the only way to detect them.
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken");
            }
        };
        PrintWriter mockWriter = new PrintWriter(broken, true);
        java.util.concurrent.atomic.AtomicBoolean disconnectCalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        SessionHeartbeat heartbeat = new SessionHeartbeat(mockWriter, null, 10L, () -> disconnectCalled.set(true));
        Thread t = new Thread(heartbeat);
        t.setDaemon(true);
        t.start();

        // Wait up to 2 s for the write to fail and onDisconnect to fire.
        long deadline = System.currentTimeMillis() + 2_000;
        while (!disconnectCalled.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        t.join(1_000);

        assertThat(disconnectCalled.get()).isTrue();
        assertThat(t.isAlive()).isFalse();
    }

    @Test
    void runDetectsErrorResponseAndInvokesOnDisconnect() throws Exception {
        // Use piped streams so the test can act as the mock server:
        // the heartbeat writes HEARTBEAT, the server reads it and responds
        // with an error, and the heartbeat detects the non-OK response.
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut);
        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut);

        PrintWriter writer = new PrintWriter(clientOut, true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
        AtomicBoolean disconnectCalled = new AtomicBoolean(false);

        SessionHeartbeat hb = new SessionHeartbeat(writer, reader, 10L, () -> disconnectCalled.set(true));
        Thread t = new Thread(hb);
        t.setDaemon(true);
        t.start();

        // Read the HEARTBEAT line sent by the heartbeat thread.
        BufferedReader serverReader = new BufferedReader(new InputStreamReader(serverIn, StandardCharsets.UTF_8));
        String heartbeatLine = serverReader.readLine();
        assertThat(heartbeatLine).isEqualTo("HEARTBEAT");

        // Send an error response back to the heartbeat.
        PrintWriter serverWriter = new PrintWriter(serverOut, true);
        serverWriter.println("ERROR unknown session");

        // Wait for the heartbeat thread to detect the error and stop.
        long deadline = System.currentTimeMillis() + 2_000;
        while (!disconnectCalled.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        t.join(1_000);

        assertThat(disconnectCalled.get()).isTrue();
        assertThat(t.isAlive()).isFalse();
    }
}
