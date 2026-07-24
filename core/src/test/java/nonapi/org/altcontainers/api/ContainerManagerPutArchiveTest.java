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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerException;
import org.altcontainers.api.ContainerSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for {@link ContainerManager#putArchive} error propagation,
 * pre-validation, and thread-leak prevention.
 */
@Tag("docker")
class ContainerManagerPutArchiveTest {

    static boolean dockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean reaperJarAvailable() {
        return ContainerManagerPutArchiveTest.class.getClassLoader().getResource("reaper.jar") != null;
    }

    static boolean dockerAndReaperAvailable() {
        return dockerAvailable() && reaperJarAvailable();
    }

    /**
     * Walks the exception chain (cause and suppressed) looking for a message substring.
     */
    private static boolean hasMessageInChain(Throwable t, String fragment) {
        if (t == null) {
            return false;
        }
        if (t.getMessage() != null && t.getMessage().contains(fragment)) {
            return true;
        }
        if (hasMessageInChain(t.getCause(), fragment)) {
            return true;
        }
        for (Throwable suppressed : t.getSuppressed()) {
            if (hasMessageInChain(suppressed, fragment)) {
                return true;
            }
        }
        return false;
    }

    @AfterEach
    void assertNoLeakedWriterThreads() throws InterruptedException {
        // Give daemon threads a moment to exit after pipedIn.close()
        Thread.sleep(100);
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        assertThat(threads).noneMatch(t -> t.getName().equals("altcontainers-core-tar-writer"));
    }

    @Test
    void shouldFailFastWhenFileNameExceeds100Utf8Bytes() {
        ContainerManager manager = ContainerManager.getInstance();
        String longName = "a".repeat(101); // 101 ASCII bytes = 101 UTF-8 bytes

        assertThatThrownBy(() ->
                        manager.putArchive("nonexistent-container-id", "/tmp", longName, new byte[] {1, 2, 3}, 0644))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Tar entry name is too long");
    }

    @Test
    void shouldFailFastWhenFileNameExceeds100Utf8BytesMultiByte() {
        ContainerManager manager = ContainerManager.getInstance();
        // Each CJK character is 3 UTF-8 bytes; 34 x 3 = 102 > 100
        String longName = "\u4E16\u754C".repeat(17); // 34 chars, 102 bytes

        assertThatThrownBy(() ->
                        manager.putArchive("nonexistent-container-id", "/tmp", longName, new byte[] {1, 2, 3}, 0644))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Tar entry name is too long");
    }

    @Test
    void shouldPassThroughWhenFileNameIsExactly100Utf8Bytes() {
        ContainerManager manager = ContainerManager.getInstance();
        String exactly100 = "a".repeat(100);

        assertThatThrownBy(() ->
                        manager.putArchive("nonexistent-container-id", "/tmp", exactly100, new byte[] {1, 2, 3}, 0644))
                .isInstanceOf(ContainerException.class)
                .satisfies(e -> {
                    // Pre-validation must NOT fire for a 100-byte name
                    assertThat(e.getMessage()).doesNotContain("Tar entry name is too long");
                    // Execution must have reached the daemon call (which fails)
                    assertThat(e.getMessage()).contains("Failed to copy archive to container");
                });
    }

    @Test
    void shouldPropagateWriterErrorForTruncatedContentStream() {
        ContainerManager manager = ContainerManager.getInstance();
        InputStream truncated = new InputStream() {
            private int remaining = 10;

            @Override
            public int read() {
                if (remaining-- > 0) {
                    return 42;
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int toRead = Math.min(len, remaining);
                for (int i = 0; i < toRead; i++) {
                    b[off + i] = 42;
                }
                remaining -= toRead;
                return toRead;
            }
        };

        assertThatThrownBy(() -> manager.putArchive(
                        "nonexistent-container-id",
                        "/tmp",
                        "short.txt",
                        truncated,
                        100L, // claims 100 bytes but only provides 10
                        0644))
                .isInstanceOf(ContainerException.class)
                .satisfies(e -> {
                    boolean found = hasMessageInChain(e, "Unexpected end of content stream");
                    assertThat(found)
                            .as("Writer error 'Unexpected end of content stream' must appear "
                                    + "in exception chain (root cause or suppressed)")
                            .isTrue();
                });
    }

    @Test
    @EnabledIf("dockerAndReaperAvailable")
    void shouldCopyFileToContainerAndReadBack() throws Exception {
        ContainerSpec spec =
                ContainerSpec.builder("alpine:latest").command("sleep", "30").build();
        try (Container container = Container.create(spec)) {
            byte[] content = "Hello, Altcontainers!".getBytes(StandardCharsets.UTF_8);
            container.copyFileToContainer("/tmp", "test.txt", content, 0644);

            // Read back the file content via exec
            String execOutput = execAndCapture(container, "cat", "/tmp/test.txt");
            assertThat(execOutput).isEqualTo("Hello, Altcontainers!");
        }
    }

    /**
     * Executes a command in a container and captures stdout.
     */
    private static String execAndCapture(Container container, String... command) throws Exception {
        com.github.dockerjava.api.command.ExecCreateCmdResponse createResponse = DockerClientFactory.client()
                .execCreateCmd(container.id())
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder output = new StringBuilder();
        try {
            DockerClientFactory.client()
                    .execStartCmd(createResponse.getId())
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame.getStreamType() == StreamType.STDOUT) {
                                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerException("Interrupted while reading exec output", e);
        }
        return output.toString().stripTrailing();
    }
}
