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

import static org.assertj.core.api.Assertions.assertThat;

import nonapi.org.altcontainers.docker.DockerContainerInspect;
import org.altcontainers.api.ContainerException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DockerContainerInspect#diagnostics()} — the building
 * block used by {@link ContainerManager#createContainer} for diagnostic
 * enrichment of failure messages.
 */
class ContainerManagerDiagnosticsTest {

    @Test
    void diagnosticsIncludesOomKilled() {
        DockerContainerInspect inspect = new DockerContainerInspect(
                false, /* running */
                true, /* oomKilled */
                false, /* dead */
                137, /* exitCode */
                null, /* error */
                java.util.Map.of(),
                java.util.Map.of());
        assertThat(inspect.diagnostics()).contains("container was OOMKilled");
        assertThat(inspect.diagnostics()).contains("exitCode=137");
    }

    @Test
    void diagnosticsIncludesDeadAndError() {
        DockerContainerInspect inspect = new DockerContainerInspect(
                false, /* running */
                false, /* oomKilled */
                true, /* dead */
                null, /* exitCode */
                "something went wrong",
                java.util.Map.of(),
                java.util.Map.of());
        assertThat(inspect.diagnostics()).contains("container is dead");
        assertThat(inspect.diagnostics()).contains("error=something went wrong");
    }

    @Test
    void diagnosticsEmptyForHealthyContainer() {
        DockerContainerInspect inspect = new DockerContainerInspect(
                true, /* running */
                false, /* oomKilled */
                false, /* dead */
                null, /* exitCode */
                null, /* error */
                java.util.Map.of(),
                java.util.Map.of());
        assertThat(inspect.diagnostics()).isEmpty();
    }

    @Test
    void diagnosticsEnrichmentPreservesOriginalStackTrace() {
        // Simulate the enrichment pattern used by ContainerManager when
        // augmenting a ContainerException with diagnostic information.
        ContainerException original = new ContainerException("Failed to start container abc123");
        String diagnostics = "container was OOMKilled; exitCode=137";

        ContainerException enriched =
                new ContainerException(original.getMessage() + " [" + diagnostics + "]", original.getCause());
        enriched.setStackTrace(original.getStackTrace());

        assertThat(enriched.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(enriched.getMessage()).contains("container was OOMKilled");
        assertThat(enriched.getMessage()).contains("Failed to start container abc123");
    }
}
