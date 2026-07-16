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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReaperConnection} thread safety.
 */
class ReaperConnectionTest {

    private static final int THREAD_COUNT = 4;

    /**
     * Verifies that concurrent writes to ReaperConnection produce complete,
     * non-interleaved commands. Each thread writes a distinct command via a
     * shared ReaperConnection backed by a piped stream. The reader thread
     * collects all lines and asserts that each is a well-formed, complete
     * command.
     *
     * <p>This test validates the thread-safety fix that adds
     * {@code synchronized (writer)} blocks to the write methods. Without
     * synchronization, concurrent writes from {@code ContainerManager} and
     * {@code NetworkManager} delegation paths could interleave bytes on the
     * wire, producing corrupted protocol commands.
     */
    @Test
    void concurrentWritesShouldProduceCompleteCommands() throws Exception {
        PipedInputStream pipedIn = new PipedInputStream(8192);
        PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

        Socket mockSocket = new Socket() {
            @Override
            public java.io.OutputStream getOutputStream() {
                return pipedOut;
            }

            @Override
            public void close() throws IOException {
                pipedOut.close();
            }
        };

        ReaperConnection connection = new ReaperConnection(mockSocket);

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService writers = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int index = i;
            futures.add(writers.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    connection.sendTerminateContainer("container-" + index);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        writers.shutdown();

        pipedOut.close();

        Pattern expectedPattern = Pattern.compile("^TERMINATE_CONTAINER container-\\d+$");

        List<String> lines = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(pipedIn, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        assertThat(lines).hasSize(THREAD_COUNT);
        for (String line : lines) {
            assertThat(line).matches(expectedPattern);
        }
    }
}
