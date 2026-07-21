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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code onOutput} consumers receive container output frames
 * after the container becomes ready (for the lifetime of the container).
 *
 * <p>These tests require a running Docker daemon.
 */
@Tag("docker")
class OutputStreamingTest {

    /**
     * A container that prints a readiness marker, then keeps printing {@code TICK}
     * once per second.
     */
    private static final String COMMAND =
            "echo READY; i=0; while [ $i -lt 60 ]; do echo TICK; sleep 1; i=$((i+1)); done";

    /**
     * An {@code onOutput} consumer must receive output frames emitted after the container
     * becomes ready.
     *
     * <p>Readiness is satisfied by the {@code READY} line (consumed during startup
     * by a {@code LogWaitStrategy}). After {@code Container.create} returns, any
     * {@code TICK} captured during startup is discarded, then the consumer is
     * polled for a fresh post-startup {@code TICK}.
     */
    @Test
    void onOutputReceivesFramesAfterReadiness() throws Exception {
        List<String> ticks = Collections.synchronizedList(new ArrayList<>());
        ContainerSpec spec = ContainerSpec.builder("alpine:latest")
                .command("sh", "-c", COMMAND)
                .waitForLogMessage(".*READY.*")
                .outputListener(frame -> {
                    String line = frame.utf8StringWithoutLineEnding();
                    if (line.contains("TICK")) {
                        ticks.add(line);
                    }
                })
                .startupTimeout(Duration.ofSeconds(30))
                .build();
        try (Container container = Container.create(spec)) {
            // Discard any TICK captured during startup; we want post-startup delivery.
            ticks.clear();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && ticks.isEmpty()) {
                Thread.sleep(200);
            }
            assertThat(ticks).isNotEmpty();
        }
    }
}
