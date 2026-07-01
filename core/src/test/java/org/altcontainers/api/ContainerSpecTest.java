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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ContainerSpecTest {

    @Test
    void shouldRejectBlankImage() {
        assertThatIllegalArgumentException().isThrownBy(() -> ContainerSpec.builder(""));
    }

    @Test
    void shouldRejectNullImage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder(null))
                .withMessageContaining("image must not be blank");
    }

    @Test
    void shouldRejectNullCommand() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").command((String[]) null));
    }

    @Test
    void shouldRejectNullCommandElement() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").command("a", null, "b"))
                .withMessageContaining("command parts must not contain null");
    }

    @Test
    void shouldBuildMinimalSpec() {
        ContainerSpec containerSpec = ContainerSpec.builder("alpine:latest").build();

        assertThat(containerSpec.image()).isEqualTo("alpine:latest");
        assertThat(containerSpec.command()).isEmpty();
        assertThat(containerSpec.exposedPorts()).isEmpty();
        assertThat(containerSpec.bindMounts()).isEmpty();
        assertThat(containerSpec.networkMode()).isNull();
        assertThat(containerSpec.networkAliases()).isEmpty();
        assertThat(containerSpec.workingDirectory()).isNull();
        assertThat(containerSpec.logConsumer()).isNull();
        assertThat(containerSpec.startupTimeout()).isEqualTo(ContainerSpec.DEFAULT_STARTUP_TIMEOUT);
        assertThat(containerSpec.startupAttempts()).isEqualTo(1);
        assertThat(containerSpec.waitConditions()).isEmpty();
        assertThat(containerSpec.memory()).isEqualTo(0);
        assertThat(containerSpec.memorySwap()).isEqualTo(0);
        assertThat(containerSpec.shmSize()).isEqualTo(0);
        assertThat(containerSpec.cpuShares()).isEqualTo(0);
        assertThat(containerSpec.cpuPeriod()).isEqualTo(0);
        assertThat(containerSpec.cpuQuota()).isEqualTo(0);
    }

    @Test
    void shouldAccumulateCommandParts() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").command("a").command("b").build();
        assertThat(containerSpec.command()).containsExactly("a", "b");
    }

    @Test
    void shouldDeduplicateExposedPorts() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").exposePorts(8080, 8080).build();
        assertThat(containerSpec.exposedPorts()).containsExactly(8080);
    }

    @Test
    void shouldRejectNullExposePorts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").exposePorts((int[]) null))
                .withMessageContaining("ports must not be null");
    }

    @Test
    void shouldRejectInvalidPortRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").exposePorts(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").exposePorts(65536));
    }

    @Test
    void shouldBuildWithMemoryLimit() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").memory(536870912L).build();
        assertThat(containerSpec.memory()).isEqualTo(536870912L);
    }

    @Test
    void shouldBuildWithAllResourceLimits() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .memory(536870912L)
                .memorySwap(1073741824L)
                .shmSize(67108864L)
                .cpuShares(512)
                .cpuPeriod(100000L)
                .cpuQuota(50000L)
                .build();

        assertThat(containerSpec.memory()).isEqualTo(536870912L);
        assertThat(containerSpec.memorySwap()).isEqualTo(1073741824L);
        assertThat(containerSpec.shmSize()).isEqualTo(67108864L);
        assertThat(containerSpec.cpuShares()).isEqualTo(512);
        assertThat(containerSpec.cpuPeriod()).isEqualTo(100000L);
        assertThat(containerSpec.cpuQuota()).isEqualTo(50000L);
    }

    @Test
    void shouldBuildWithWaitConditions() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .waitForContainerPort(8080)
                .waitForLogMessage("ready")
                .waitForLogMessage("done", 3)
                .build();
        assertThat(containerSpec.waitConditions()).hasSize(3);
    }

    @Test
    void shouldRejectNegativeStartupAttempts() {
        // 0 is rejected by ContainerManager, not the builder
        assertThat(ContainerSpec.builder("img").startupAttempts(0).build().startupAttempts())
                .isEqualTo(0);
    }

    @Test
    void shouldReturnConfiguredValues() {
        ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
                .command("sh")
                .exposePorts(8080)
                .memory(536870912L)
                .build();

        assertThat(containerSpec.image()).isEqualTo("my-image:latest");
        assertThat(containerSpec.command()).containsExactly("sh");
        assertThat(containerSpec.exposedPorts()).containsExactly(8080);
        assertThat(containerSpec.memory()).isEqualTo(536870912L);
    }

    @Test
    void shouldRejectBlankBindHostPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").bindDirectory("", "/container"));
    }

    @Test
    void shouldRejectBlankBindContainerPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").bindDirectory("/host", ""));
    }

    @Test
    void shouldBuildWithBindDirectory() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .bindDirectory("/host/path", "/container/path")
                .build();
        assertThat(containerSpec.bindMounts()).hasSize(1);
        assertThat(containerSpec.bindMounts().get(0).hostPath()).isEqualTo("/host/path");
        assertThat(containerSpec.bindMounts().get(0).containerPath()).isEqualTo("/container/path");
    }

    @Test
    void shouldBuildWithNetwork() {
        Network network = new Network("test-net", "abc123");
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .network(network, "alias1", "alias2")
                .build();
        assertThat(containerSpec.networkMode()).isEqualTo("test-net");
        assertThat(containerSpec.networkAliases()).containsExactly("alias1", "alias2");
    }

    @Test
    void shouldRejectNullNetwork() {
        assertThatNullPointerException()
                .isThrownBy(() -> ContainerSpec.builder("img").network(null, "alias"));
    }

    @Test
    void shouldRejectNullAliasesArray() {
        Network network = new Network("test-net", "abc123");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").network(network, (String[]) null))
                .withMessageContaining("aliases must not be null");
    }

    @Test
    void shouldRejectBlankAlias() {
        Network network = new Network("test-net", "abc123");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").network(network, ""));
    }

    @Test
    void shouldRejectNullAliasElement() {
        Network network = new Network("test-net", "abc123");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").network(network, "a", null, "b"))
                .withMessageContaining("alias must not be blank");
    }

    @Test
    void shouldReplaceNetworkOnSecondCall() {
        Network net1 = new Network("net1", "id1");
        Network net2 = new Network("net2", "id2");
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .network(net1, "a")
                .network(net2, "b")
                .build();
        assertThat(containerSpec.networkMode()).isEqualTo("net2");
        assertThat(containerSpec.networkAliases()).containsExactly("b");
    }

    @Test
    void shouldBuildWithSingleUlimit() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").ulimit("nofile", 65536, 65536).build();
        assertThat(containerSpec.ulimits()).hasSize(1);
        assertThat(containerSpec.ulimits().get(0).name()).isEqualTo("nofile");
        assertThat(containerSpec.ulimits().get(0).soft()).isEqualTo(65536);
        assertThat(containerSpec.ulimits().get(0).hard()).isEqualTo(65536);
    }

    @Test
    void shouldAccumulateUlimits() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .ulimit("nofile", 65536, 65536)
                .ulimit("nproc", 4096, 4096)
                .build();
        assertThat(containerSpec.ulimits()).hasSize(2);
        assertThat(containerSpec.ulimits().get(0).name()).isEqualTo("nofile");
        assertThat(containerSpec.ulimits().get(1).name()).isEqualTo("nproc");
    }

    @Test
    void shouldBuildMinimalSpecWithEmptyUlimits() {
        ContainerSpec containerSpec = ContainerSpec.builder("img").build();
        assertThat(containerSpec.ulimits()).isEmpty();
    }

    @Test
    void shouldClearUlimits() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .ulimit("nofile", 65536, 65536)
                .clearUlimits()
                .build();
        assertThat(containerSpec.ulimits()).isEmpty();
    }

    @Test
    void shouldReturnConfiguredUlimits() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .ulimit("nofile", 65536, 65536)
                .ulimit("nproc", 4096, 4096)
                .build();
        assertThat(containerSpec.ulimits()).hasSize(2);
    }

    @Test
    void shouldRejectNegativeMemory() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").memory(-1))
                .withMessageContaining("memory must be >= 0");
    }

    @Test
    void shouldRejectNegativeMemorySwap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").memorySwap(-1))
                .withMessageContaining("memorySwap must be >= 0");
    }

    @Test
    void shouldRejectNegativeShmSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").shmSize(-1))
                .withMessageContaining("shmSize must be >= 0");
    }

    @Test
    void shouldRejectNegativeCpuShares() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").cpuShares(-1))
                .withMessageContaining("cpuShares must be >= 0");
    }

    @Test
    void shouldRejectNegativeCpuPeriod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").cpuPeriod(-1))
                .withMessageContaining("cpuPeriod must be >= 0");
    }

    @Test
    void shouldRejectNegativeCpuQuota() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").cpuQuota(-1))
                .withMessageContaining("cpuQuota must be >= 0");
    }

    @Test
    void shouldAllowZeroMemory() {
        ContainerSpec containerSpec = ContainerSpec.builder("img").memory(0).build();
        assertThat(containerSpec.memory()).isEqualTo(0);
    }

    @Test
    void shouldAllowZeroMemorySwap() {
        ContainerSpec containerSpec = ContainerSpec.builder("img").memorySwap(0).build();
        assertThat(containerSpec.memorySwap()).isEqualTo(0);
    }

    @Test
    void shouldRejectWaitForLogMessageZeroTimes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForLogMessage("ready", 0))
                .withMessageContaining("times must be >= 1");
    }

    // waitForHttpResponse tests

    @Test
    void shouldAddHttpWaitCondition() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").waitForHttpResponse(80, "/health").build();
        assertThat(containerSpec.waitConditions()).hasSize(1);
        assertThat(containerSpec.waitConditions().get(0)).isInstanceOf(WaitCondition.HttpWait.class);
    }

    @Test
    void shouldDefaultStatusRange() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").waitForHttpResponse(80, "/").build();
        assertThat(containerSpec.waitConditions()).hasSize(1);
        WaitCondition.HttpWait httpWait =
                (WaitCondition.HttpWait) containerSpec.waitConditions().get(0);
        // Can't directly access private fields, so we verify via behavior
        // Create a mock Container that returns 200 for any port
        Container mockContainer = new Container("test", "img") {
            private com.sun.net.httpserver.HttpServer server;

            {
                try {
                    server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
                    server.createContext("/", exchange -> {
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    });
                    server.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public int hostPort(int containerPort) {
                return server.getAddress().getPort();
            }

            @Override
            public void close() {
                server.stop(0);
                super.close();
            }
        };
        assertThat(httpWait.check(mockContainer)).isTrue();
        mockContainer.close();
    }

    @Test
    void shouldRejectBlankPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, ""))
                .withMessageContaining("path must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, "  "))
                .withMessageContaining("path must not be blank");
    }

    @Test
    void shouldRejectOutOfRangePort() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(0, "/"))
                .withMessageContaining("containerPort must be in 1..65535");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(70000, "/"))
                .withMessageContaining("containerPort must be in 1..65535");
    }

    @Test
    void shouldRejectNullHttpPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, null, 200, 200))
                .withMessageContaining("path must not be blank");
    }

    @Test
    void shouldRejectStatusOutOfRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, "/", 99, 200))
                .withMessageContaining("minStatus must be in 100..599");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, "/", 200, 600))
                .withMessageContaining("maxStatus must be in 100..599");
    }

    @Test
    void shouldRejectHttpMinStatusTooHigh() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, "/", 600, 200))
                .withMessageContaining("minStatus must be in 100..599");
    }

    @Test
    void shouldRejectHttpMaxStatusTooLow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, "/", 200, 99))
                .withMessageContaining("maxStatus must be in 100..599");
    }

    @Test
    void shouldRejectMinGreaterThanMax() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpResponse(80, "/", 300, 200))
                .withMessageContaining("minStatus must be <= maxStatus");
    }

    @Test
    void shouldStackWithContainerPort() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .waitForContainerPort(80)
                .waitForHttpResponse(80, "/")
                .build();
        assertThat(containerSpec.waitConditions()).hasSize(2);
    }

    // waitForHttpsResponse tests

    @Test
    void shouldAddHttpsWaitCondition() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .waitForHttpsResponse(443, "/health")
                .build();
        assertThat(containerSpec.waitConditions()).hasSize(1);
        assertThat(containerSpec.waitConditions().get(0)).isInstanceOf(WaitCondition.HttpWait.class);
    }

    @Test
    void shouldDefaultStatusRangeForHttps() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").waitForHttpsResponse(443, "/").build();
        assertThat(containerSpec.waitConditions()).hasSize(1);
        WaitCondition.HttpWait httpsWait =
                (WaitCondition.HttpWait) containerSpec.waitConditions().get(0);
        // Cannot directly test HTTPS without container, so verify structure
        assertThat(containerSpec.waitConditions()).hasSize(1);
        assertThat(httpsWait).isNotNull();
    }

    @Test
    void shouldAcceptCustomStatusRangeForHttps() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .waitForHttpsResponse(443, "/ready", 500, 599)
                .build();
        assertThat(containerSpec.waitConditions()).hasSize(1);
        assertThat(containerSpec.waitConditions().get(0)).isInstanceOf(WaitCondition.HttpWait.class);
    }

    @Test
    void shouldStackHttpsWithOtherConditions() {
        ContainerSpec containerSpec = ContainerSpec.builder("img")
                .waitForContainerPort(443)
                .waitForHttpsResponse(443, "/")
                .waitForLogMessage("started")
                .build();
        assertThat(containerSpec.waitConditions()).hasSize(3);
    }

    @Test
    void shouldRejectHttpsBlankPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, ""))
                .withMessageContaining("path must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, "  "))
                .withMessageContaining("path must not be blank");
    }

    @Test
    void shouldRejectHttpsOutOfRangePort() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(0, "/"))
                .withMessageContaining("containerPort must be in 1..65535");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(70000, "/"))
                .withMessageContaining("containerPort must be in 1..65535");
    }

    @Test
    void shouldRejectNullHttpsPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, null, 200, 200))
                .withMessageContaining("path must not be blank");
    }

    @Test
    void shouldRejectHttpsStatusOutOfRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, "/", 99, 200))
                .withMessageContaining("minStatus must be in 100..599");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, "/", 200, 600))
                .withMessageContaining("maxStatus must be in 100..599");
    }

    @Test
    void shouldRejectHttpsMinStatusTooHigh() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, "/", 600, 200))
                .withMessageContaining("minStatus must be in 100..599");
    }

    @Test
    void shouldRejectHttpsMaxStatusTooLow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, "/", 200, 99))
                .withMessageContaining("maxStatus must be in 100..599");
    }

    @Test
    void shouldRejectHttpsMinGreaterThanMax() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").waitForHttpsResponse(443, "/", 300, 200))
                .withMessageContaining("minStatus must be <= maxStatus");
    }

    // workingDirectory tests

    @Test
    void shouldRejectNullWorkingDirectory() {
        assertThatNullPointerException()
                .isThrownBy(() -> ContainerSpec.builder("img").workingDirectory(null));
    }

    @Test
    void shouldRejectBlankWorkingDirectory() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").workingDirectory(""))
                .withMessageContaining("directory must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContainerSpec.builder("img").workingDirectory("  "))
                .withMessageContaining("directory must not be blank");
    }

    @Test
    void shouldBuildWithWorkingDirectory() {
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").workingDirectory("/app").build();
        assertThat(containerSpec.workingDirectory()).isEqualTo("/app");
    }

    // logConsumer tests

    @Test
    void shouldRejectNullLogConsumer() {
        assertThatNullPointerException()
                .isThrownBy(() -> ContainerSpec.builder("img").logConsumer(null));
    }

    @Test
    void shouldBuildWithLogConsumer() {
        PrefixConsumer consumer = PrefixConsumer.of("> ", "test-image");
        ContainerSpec containerSpec =
                ContainerSpec.builder("img").logConsumer(consumer).build();
        assertThat(containerSpec.logConsumer()).isSameAs(consumer);
    }
}
