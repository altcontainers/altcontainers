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
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Objects;
import javax.net.SocketFactory;

/**
 * {@link SocketFactory} that creates {@link JdkUnixSocket} instances connecting to a configured
 * Unix domain socket path.
 *
 * <p>The TCP host/port arguments passed to the factory methods are ignored; every socket connects
 * to the same Unix socket path.
 */
public final class JdkUnixSocketFactory extends SocketFactory {

    private final Path socketPath;

    /**
     * Creates a factory for the given Unix socket path.
     *
     * @param socketPath the Unix domain socket path; must not be {@code null}
     */
    public JdkUnixSocketFactory(Path socketPath) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath must not be null");
    }

    @Override
    public Socket createSocket() throws IOException {
        return new JdkUnixSocket(socketPath);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return new JdkUnixSocket(socketPath);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return new JdkUnixSocket(socketPath);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return new JdkUnixSocket(socketPath);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return new JdkUnixSocket(socketPath);
    }
}
