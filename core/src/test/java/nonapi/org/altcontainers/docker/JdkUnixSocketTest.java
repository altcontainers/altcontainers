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

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkUnixSocketTest {

    @Test
    void connectIsSafeUnderConcurrentAccess(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("test.sock");
        startAcceptingServer(socketPath);

        JdkUnixSocket socket = new JdkUnixSocket(socketPath);

        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable task = () -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                socket.connect(null, 0);
            } catch (IOException e) {
                // connect should succeed for at least one thread;
                // the other should return silently via CAS guard.
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join(2000);
        t2.join(2000);

        assertThat(socket.isConnected()).isTrue();
    }

    @Test
    void shouldCloseChannelWhenInputStreamClosed(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("test-close.sock");
        startAcceptingServer(socketPath);

        JdkUnixSocket socket = new JdkUnixSocket(socketPath);
        socket.connect(null, 0);

        java.io.InputStream inputStream = socket.getInputStream();
        inputStream.close();

        // After closing the InputStream, the underlying SocketChannel must be closed.
        assertThat(socket.isClosed())
                .as("Socket must be closed after InputStream.close()")
                .isTrue();
    }

    @Test
    void shouldTimeoutWhenConnectingToNonexistentPath(@TempDir Path tempDir) throws Exception {
        // A nonexistent Unix socket path: connect should fail (not hang) with the new
        // timeout support. On Linux, connecting to a nonexistent path throws
        // immediately; the timeout codepath handles this correctly and completes
        // within a bounded time.
        Path nonexistent = tempDir.resolve("nonexistent.sock");
        JdkUnixSocket socket = new JdkUnixSocket(nonexistent);
        long start = System.currentTimeMillis();
        try {
            socket.connect(null, 500);
            // If connect somehow "succeeds" (future OS behavior), close the socket.
            socket.close();
        } catch (IOException e) {
            // Expected: connect to nonexistent Unix socket fails.
        }
        long elapsed = System.currentTimeMillis() - start;
        // Must complete well within the timeout window, not hang indefinitely.
        assertThat(elapsed).isLessThan(2000L);
    }

    private static void startAcceptingServer(Path socketPath) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
        Thread serverThread = new Thread(() -> {
            try {
                var client = server.accept();
                client.close();
                server.close();
            } catch (IOException e) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // Best-effort.
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        // Give the server a moment to bind and listen.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
