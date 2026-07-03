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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link Socket} adapter that connects OkHttp to a Unix domain socket via Java 17
 * {@link SocketChannel}.
 *
 * <p>Docker Unix socket connections are synthetic: the TCP host/port passed by OkHttp is ignored,
 * and the socket connects to the configured Unix socket path instead.
 *
 * <p>The channel operates in blocking mode for normal I/O. Timeout support is implemented via
 * a {@link Selector}-based read wrapper so that OkHttp's {@code isHealthy} connection-pool
 * check (which sets a 1 ms read timeout) completes without blocking indefinitely.
 */
final class JdkUnixSocket extends Socket {

    private final Path socketPath;
    private final SocketChannel channel;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile boolean closed;
    private volatile int soTimeout;

    /**
     * Creates a new socket for the given Unix socket path.
     *
     * @param socketPath the Unix domain socket path; must not be {@code null}
     * @throws IOException if the channel cannot be opened
     */
    JdkUnixSocket(Path socketPath) throws IOException {
        super();
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath must not be null");
        this.channel = SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        this.channel.configureBlocking(true);
    }

    /**
     * Connects to the configured Unix socket path. The {@code endpoint} parameter is ignored;
     * the socket always connects to the configured Unix domain socket path.
     *
     * @param endpoint ignored
     * @param timeout connect timeout in milliseconds; 0 means infinite
     * @throws IOException if the connection fails
     */
    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (!connected.compareAndSet(false, true)) {
            return;
        }
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        if (timeout <= 0) {
            channel.configureBlocking(true);
            try {
                channel.connect(address);
            } catch (IOException | RuntimeException e) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Best-effort.
                }
                connected.set(false);
                throw e;
            }
            return;
        }
        // Non-blocking connect with timeout.
        channel.configureBlocking(false);
        try {
            channel.connect(address);
            // connect returns true immediately if the connection is already established.
            if (channel.finishConnect()) {
                channel.configureBlocking(true);
                return;
            }
            Selector selector = Selector.open();
            try {
                channel.register(selector, SelectionKey.OP_CONNECT);
                int ready = selector.select(timeout);
                if (ready == 0) {
                    throw new SocketTimeoutException("connect timed out");
                }
                if (!channel.finishConnect()) {
                    throw new SocketTimeoutException("connect failed");
                }
            } finally {
                selector.close();
            }
            channel.configureBlocking(true);
        } catch (IOException | RuntimeException e) {
            // On failure, close the channel so the socket is not left in a half-open state.
            try {
                channel.close();
            } catch (IOException ignored) {
                // Best-effort.
            }
            connected.set(false);
            throw e;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new TimeoutInputStream(channel, this);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return java.nio.channels.Channels.newOutputStream(channel);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        channel.close();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        this.soTimeout = timeout;
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return soTimeout;
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        // No-op for Unix sockets.
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return false;
    }

    @Override
    public boolean isConnected() {
        return connected.get() && !closed;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // --- Unsupported TCP-only overrides ---

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    @Override
    public int getPort() {
        return 0;
    }

    @Override
    public int getLocalPort() {
        return 0;
    }

    /**
     * {@link InputStream} that wraps a blocking {@link SocketChannel} and applies the
     * socket's read timeout via {@link Selector} polling.
     *
     * <p>When a non-zero timeout is set, the wrapper polls the channel with a
     * {@link Selector} before each bulk read, switching the channel to non-blocking
     * mode during the poll and restoring blocking mode before the actual read. Reads
     * with zero timeout (the default) pass through directly to the channel.
     */
    private static final class TimeoutInputStream extends InputStream {

        private final SocketChannel channel;
        private final JdkUnixSocket socket;

        TimeoutInputStream(SocketChannel channel, JdkUnixSocket socket) {
            this.channel = channel;
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int timeout = socket.soTimeout;
            if (timeout <= 0) {
                return channel.read(ByteBuffer.wrap(b, off, len));
            }

            Selector selector = Selector.open();
            try {
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ);
                int ready = selector.select(timeout);
                SelectionKey key = channel.keyFor(selector);
                if (key != null) {
                    key.cancel();
                    selector.selectNow();
                }
                channel.configureBlocking(true);
                if (ready == 0) {
                    throw new SocketTimeoutException("Read timed out");
                }
                return channel.read(ByteBuffer.wrap(b, off, len));
            } finally {
                selector.close();
                if (!channel.isBlocking()) {
                    channel.configureBlocking(true);
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
