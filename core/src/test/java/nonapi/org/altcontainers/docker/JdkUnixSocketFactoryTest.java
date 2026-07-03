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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkUnixSocketFactoryTest {

    @Test
    void connectsOkHttpToUnixSocketServer(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("test.sock");
        startUnixEchoServer(socketPath);

        OkHttpClient client = new OkHttpClient.Builder()
                .socketFactory(new JdkUnixSocketFactory(socketPath))
                .dns(hostname -> {
                    if ("docker".equals(hostname)) {
                        return List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
                    }
                    throw new UnknownHostException(hostname);
                })
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder().url("http://docker/_ping").build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().string()).isEqualTo("OK");
        }
    }

    @Test
    void createSocketReturnsUnconnectedSocket(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("test.sock");
        JdkUnixSocketFactory factory = new JdkUnixSocketFactory(socketPath);

        try (Socket socket = factory.createSocket()) {
            assertThat(socket).isNotNull();
            assertThat(socket.isConnected()).isFalse();
        }
    }

    @Test
    void closeIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("test.sock");
        JdkUnixSocketFactory factory = new JdkUnixSocketFactory(socketPath);

        Socket socket = factory.createSocket();
        socket.close();
        assertThat(socket.isClosed()).isTrue();

        assertThatCode(() -> socket.close()).doesNotThrowAnyException();
        assertThat(socket.isClosed()).isTrue();
    }

    private static void startUnixEchoServer(Path socketPath) throws IOException {
        var server = java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(java.net.UnixDomainSocketAddress.of(socketPath));

        Thread serverThread = new Thread(() -> {
            try {
                var client = server.accept();
                InputStream in = java.nio.channels.Channels.newInputStream(client);
                OutputStream out = java.nio.channels.Channels.newOutputStream(client);

                // Read the HTTP request (ignore it) and send a minimal 200 OK response
                byte[] buf = new byte[4096];
                int read = in.read(buf);
                // Send HTTP/1.1 200 OK with body "OK"
                String response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
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
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
