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

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A closeable handle to a Docker container log follow-stream opened by
 * {@link DockerClient#attachLogStream(String, java.util.function.Consumer, java.util.function.Consumer)}.
 *
 * <p>Closing the handle releases the underlying docker-java follow-stream callback. Close is idempotent
 * and thread-safe: concurrent calls from multiple threads will invoke {@code callback.close()} at most
 * once. {@link DockerClient} tracks active handles and also closes them when the associated container is
 * destroyed, so callers are not required to close handles explicitly, although doing so is harmless.
 *
 * <p>{@link IOException} thrown by the underlying callback is intentionally swallowed during teardown,
 * since the container is being destroyed and the stream may already be closed.
 */
public final class LogStreamHandle implements Closeable {

    private final Closeable callback;
    private final AtomicBoolean closed;

    /**
     * Creates a handle wrapping the given closeable docker-java result callback.
     *
     * @param callback the docker-java result callback returned by {@code logContainerCmd(...).exec(...)};
     *     must not be {@code null}
     * @throws IllegalArgumentException if {@code callback} is {@code null}
     */
    public LogStreamHandle(Closeable callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        this.callback = callback;
        this.closed = new AtomicBoolean(false);
    }

    /**
     * Closes the underlying follow-stream callback.
     *
     * <p>Idempotent and thread-safe: the callback is closed at most once, even under concurrent calls.
     * Any {@link IOException} raised by the underlying close is intentionally swallowed, since the
     * container is being torn down and the stream may already be closed.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                callback.close();
            } catch (IOException ignored) {
                // Best-effort: the container is being destroyed and the stream may already be closed.
            }
        }
    }
}
