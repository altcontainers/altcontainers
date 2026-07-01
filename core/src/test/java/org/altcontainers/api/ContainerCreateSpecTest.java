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

package org.altcontainers.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import nonapi.org.altcontainers.ContainerCreateSpec;
import org.junit.jupiter.api.Test;

class ContainerCreateSpecTest {
    @Test
    void shouldRejectBlankImage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "", List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullImage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        null, List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()))
                .withMessage("image must not be blank");
    }

    @Test
    void shouldRejectNullCommand() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", null, List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullExposedPorts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), null, List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullBindMounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), List.of(), null, null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullNetworkAliases() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), List.of(), List.of(), null, null, null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullUlimits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), List.of(), List.of(), null, List.of(), null, null, 0, 0, 0, 0, 0, 0,
                        Map.of()))
                .withMessage("ulimits must not be null");
    }

    @Test
    void shouldRejectNullUlimitElement() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        Arrays.asList((Ulimit) null),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()))
                .withMessage("ulimits must not contain null elements");
    }

    @Test
    void shouldRejectNullInCommand() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        Arrays.asList("a", null),
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));
    }

    @Test
    void shouldRejectInvalidPortInExposedPorts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(0),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(65536),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullPortInExposedPorts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        Arrays.asList((Integer) null),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()))
                .withMessage("exposedPorts must not contain null elements");
    }

    @Test
    void shouldRejectNullInBindMounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(),
                        Arrays.asList((BindMount) null),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));
    }

    @Test
    void shouldRejectNullInNetworkAliases() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(),
                        List.of(),
                        "net",
                        Arrays.asList("a", null),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));
    }

    @Test
    void shouldRejectBlankInNetworkAliases() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(),
                        List.of(),
                        "net",
                        List.of(""),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));
    }

    @Test
    void shouldRejectAliasesWithoutNetwork() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img",
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of("a"),
                        null,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        Map.of()));
    }

    @Test
    void shouldRejectBlankNetworkMode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), List.of(), List.of(), "", List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldRejectBlankWorkingDirectory() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), List.of(), List.of(), null, List.of(), "", List.of(), 0, 0, 0, 0, 0, 0,
                        Map.of()));
    }

    @Test
    void shouldAcceptNullWorkingDirectory() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img", List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0, Map.of());
        assertThat(spec.workingDirectory()).isNull();
    }

    @Test
    void shouldAcceptNullNetworkMode() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img", List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0, Map.of());
        assertThat(spec.networkMode()).isNull();
    }

    @Test
    void shouldAcceptValidUlimits() {
        List<Ulimit> ulimits = List.of(new Ulimit("nofile", 65536, 65536));
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img", List.of(), List.of(), List.of(), null, List.of(), null, ulimits, 0, 0, 0, 0, 0, 0, Map.of());
        assertThat(spec.ulimits()).hasSize(1);
        assertThat(spec.ulimits().get(0).name()).isEqualTo("nofile");
    }

    @Test
    void shouldDefensivelyCopyUlimits() {
        List<Ulimit> original = new ArrayList<>();
        original.add(new Ulimit("nofile", 65536, 65536));
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img", List.of(), List.of(), List.of(), null, List.of(), null, original, 0, 0, 0, 0, 0, 0, Map.of());
        original.add(new Ulimit("nproc", 4096, 4096));
        assertThat(spec.ulimits()).hasSize(1);
    }

    @Test
    void shouldBuildValidSpec() {
        List<Ulimit> ulimits = List.of(new Ulimit("nofile", 65536, 65536), new Ulimit("nproc", 4096, 4096));
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "alpine:latest",
                List.of("sh"),
                List.of(8080),
                List.of(new BindMount("/host", "/container")),
                "mynet",
                List.of("alias1"),
                "/work",
                ulimits,
                536870912L,
                1073741824L,
                67108864L,
                512,
                100000L,
                50000L,
                Map.of());

        assertThat(spec.image()).isEqualTo("alpine:latest");
        assertThat(spec.command()).containsExactly("sh");
        assertThat(spec.exposedPorts()).containsExactly(8080);
        assertThat(spec.bindMounts()).hasSize(1);
        assertThat(spec.networkMode()).isEqualTo("mynet");
        assertThat(spec.networkAliases()).containsExactly("alias1");
        assertThat(spec.workingDirectory()).isEqualTo("/work");
        assertThat(spec.ulimits()).hasSize(2);
        assertThat(spec.ulimits().get(0).name()).isEqualTo("nofile");
        assertThat(spec.ulimits().get(1).name()).isEqualTo("nproc");
        assertThat(spec.memory()).isEqualTo(536870912L);
        assertThat(spec.memorySwap()).isEqualTo(1073741824L);
        assertThat(spec.shmSize()).isEqualTo(67108864L);
        assertThat(spec.cpuShares()).isEqualTo(512);
        assertThat(spec.cpuPeriod()).isEqualTo(100000L);
        assertThat(spec.cpuQuota()).isEqualTo(50000L);
    }

    @Test
    void shouldAcceptLabelsComponent() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of("k", "v"));
        assertThat(spec.labels()).containsEntry("k", "v");
    }

    @Test
    void shouldRejectNullLabels() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContainerCreateSpec(
                        "img", List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0,
                        null))
                .withMessage("labels must not be null");
    }

    @Test
    void shouldDefensivelyCopyLabels() {
        Map<String, String> original = new java.util.HashMap<>();
        original.put("k", "v");
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img", List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0, original);
        original.put("k2", "v2");
        assertThat(spec.labels()).hasSize(1);
        assertThat(spec.labels()).containsEntry("k", "v");
        assertThat(spec.labels()).doesNotContainKey("k2");
    }

    @Test
    void shouldBuildWithDefaultEmptyLabels() {
        ContainerCreateSpec spec = new ContainerCreateSpec(
                "img", List.of(), List.of(), List.of(), null, List.of(), null, List.of(), 0, 0, 0, 0, 0, 0, Map.of());
        assertThat(spec.labels()).isEmpty();
    }
}
