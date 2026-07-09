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

package nonapi.org.altcontainers.api;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Persistent TCP connection to the reaper process.
 * The connection itself serves as a liveness signal.
 */
final class ReaperConnection implements AutoCloseable {

    private final Socket socket;
    private final BufferedWriter writer;

    /**
     * Creates a persistent TCP connection to the reaper process.
     *
     * @param socket the connected socket
     * @throws IOException if the writer cannot be created
     */
    ReaperConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Sends TERMINATE to trigger immediate reaper cleanup.
     *
     * @throws IOException if the write fails
     */
    void sendTerminate() throws IOException {
        writer.write("TERMINATE\n");
        writer.flush();
    }

    /**
     * Sends TERMINATE_CONTAINER to delegate cleanup of a single container
     * to the reaper without shutting it down.
     *
     * @param containerId the Docker container ID
     * @throws IOException if the write fails
     */
    void sendTerminateContainer(String containerId) throws IOException {
        writer.write("TERMINATE_CONTAINER " + containerId + "\n");
        writer.flush();
    }

    /**
     * Sends TERMINATE_NETWORK to delegate cleanup of a single network
     * to the reaper without shutting it down.
     *
     * @param networkId the Docker network ID
     * @throws IOException if the write fails
     */
    void sendTerminateNetwork(String networkId) throws IOException {
        writer.write("TERMINATE_NETWORK " + networkId + "\n");
        writer.flush();
    }

    /**
     * Closes the underlying TCP socket.
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
