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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WaitTest {

    @Test
    void forListeningPortReturnsNonNull() {
        WaitStrategy result = Wait.forListeningPort(8080);
        assertThat(result).isNotNull();
    }

    @Test
    void forLogMessageReturnsNonNull() {
        WaitStrategy result = Wait.forLogMessage(".*x.*", 1);
        assertThat(result).isNotNull();
    }

    @Test
    void forHttpResponseReturnsNonNull() {
        WaitStrategy result = Wait.forHttpResponse(80, "/", Protocol.HTTP, 200, 399);
        assertThat(result).isNotNull();
    }

    @Test
    void forLogMessageCheckFalseBeforeLines() {
        WaitStrategy s = Wait.forLogMessage(".*x.*", 2);
        assertThat(s.check(null)).isFalse();
    }

    @Test
    void forLogMessageCheckTrueAfterRequiredCount() {
        LogWaitStrategy s = (LogWaitStrategy) Wait.forLogMessage(".*x.*", 2);
        s.incrementIfMatches("x\n");
        s.incrementIfMatches("x\n");
        assertThat(s.check(null)).isTrue();
    }

    @Test
    void forLogMessageNewAttemptResets() {
        LogWaitStrategy s = (LogWaitStrategy) Wait.forLogMessage(".*x.*", 1);
        s.incrementIfMatches("x\n");
        assertThat(s.check(null)).isTrue();

        WaitStrategy fresh = s.newAttemptCondition();
        assertThat(fresh.check(null)).isFalse();
        assertThat(s.check(null)).isTrue();
    }

    @Test
    void forListeningPortCheckFalseWhenPortUnmapped() {
        Container container = new Container("test", "img") {
            @Override
            public int hostPort(int containerPort) {
                return -1;
            }
        };
        WaitStrategy s = Wait.forListeningPort(8080);
        assertThat(s.check(container)).isFalse();
    }

    @Test
    void forListeningPortCheckTrueWhenPortAccepts() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("localhost", 0));
            Container container = new Container("test", "img") {
                @Override
                public int hostPort(int containerPort) {
                    return server.getLocalPort();
                }
            };
            WaitStrategy s = Wait.forListeningPort(8080);
            assertThat(s.check(container)).isTrue();
        }
    }

    private HttpServer httpServer;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void forHttpResponseCheckTrueOn200() {
        httpServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();

        Container container = new Container("test", "img") {
            @Override
            public int hostPort(int containerPort) {
                return httpServer.getAddress().getPort();
            }
        };
        WaitStrategy s = Wait.forHttpResponse(80, "/", Protocol.HTTP, 200, 399);
        assertThat(s.check(container)).isTrue();
    }

    @Test
    void forHttpResponseCheckFalseOn404() {
        httpServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        httpServer.start();

        Container container = new Container("test", "img") {
            @Override
            public int hostPort(int containerPort) {
                return httpServer.getAddress().getPort();
            }
        };
        WaitStrategy s = Wait.forHttpResponse(80, "/", Protocol.HTTP, 200, 399);
        assertThat(s.check(container)).isFalse();
    }

    @Test
    void allOfBothPass() {
        WaitStrategy s = Wait.allOf(alwaysTrue(), alwaysTrue());
        assertThat(s.check(null)).isTrue();
    }

    @Test
    void allOfOneFails() {
        WaitStrategy s = Wait.allOf(alwaysTrue(), alwaysFalse());
        assertThat(s.check(null)).isFalse();
    }

    @Test
    void allOfLogDispatchFansOut() {
        List<String> received1 = new ArrayList<>();
        List<String> received2 = new ArrayList<>();
        WaitStrategy s = Wait.allOf(logObserving(received1::add), logObserving(received2::add));

        Consumer<String> consumer = s.logLineConsumer();
        consumer.accept("hello\n");

        assertThat(received1).containsExactly("hello\n");
        assertThat(received2).containsExactly("hello\n");
    }

    @Test
    void allOfNewAttemptResetsAll() {
        LogWaitStrategy s1 = (LogWaitStrategy) Wait.forLogMessage(".*x.*", 1);
        LogWaitStrategy s2 = (LogWaitStrategy) Wait.forLogMessage("y", 1);
        s1.incrementIfMatches("x\n");

        WaitStrategy composite = Wait.allOf(s1, s2);
        assertThat(composite.check(null)).isFalse();

        WaitStrategy fresh = composite.newAttemptCondition();
        assertThat(fresh.check(null)).isFalse();
    }

    @Test
    void anyOfFirstPasses() {
        WaitStrategy s = Wait.anyOf(alwaysTrue(), alwaysFalse());
        assertThat(s.check(null)).isTrue();
    }

    @Test
    void anyOfNonePass() {
        WaitStrategy s = Wait.anyOf(alwaysFalse(), alwaysFalse());
        assertThat(s.check(null)).isFalse();
    }

    @Test
    void anyOfNewAttemptResetsAll() {
        LogWaitStrategy s1 = (LogWaitStrategy) Wait.forLogMessage(".*x.*", 1);
        s1.incrementIfMatches("x\n");

        WaitStrategy composite = Wait.anyOf(s1, alwaysFalse());
        assertThat(composite.check(null)).isTrue();

        WaitStrategy fresh = composite.newAttemptCondition();
        assertThat(fresh.check(null)).isFalse();
    }

    @Test
    void customStrategyViaInterface() {
        WaitStrategy always = c -> true;
        assertThat(always.check(null)).isTrue();
    }

    @Test
    void customStrategyWithLogObserver() {
        List<String> received = new ArrayList<>();
        WaitStrategy s = new WaitStrategy() {
            @Override
            public boolean check(Container c) {
                return true;
            }

            @Override
            public Consumer<String> logLineConsumer() {
                return received::add;
            }
        };

        Consumer<String> consumer = s.logLineConsumer();
        assertThat(consumer).isNotNull();
        consumer.accept("test\n");
        assertThat(received).containsExactly("test\n");
    }

    @Test
    void directConstructionPortWaitStrategy() {
        PortWaitStrategy result = PortWaitStrategy.builder().port(8080).build();
        assertThat(result).isNotNull();
    }

    @Test
    void directConstructionRejectsInvalidPort() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PortWaitStrategy.builder().port(0).build())
                .withMessageContaining("port must be set");
    }

    @Test
    void directConstructionRejectsPortTooHigh() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PortWaitStrategy.builder().port(65536).build())
                .withMessageContaining("port must be set");
    }

    @Test
    void directConstructionLogWaitStrategy() {
        LogWaitStrategy result = LogWaitStrategy.builder().pattern(".*x.*").build();
        assertThat(result).isNotNull();
    }

    @Test
    void directConstructionRejectsNullRegex() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogWaitStrategy.builder().pattern(null).build())
                .withMessageContaining("regex must not be blank");
    }

    @Test
    void directConstructionRejectsBlankRegex() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LogWaitStrategy.builder().pattern("").build())
                .withMessageContaining("regex must not be blank");
    }

    @Test
    void directConstructionRejectsNonPositiveRequiredCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> LogWaitStrategy.builder().pattern("x").times(0).build())
                .withMessageContaining("times must be >= 1");
    }

    @Test
    void directConstructionHttpWaitStrategy() {
        HttpWaitStrategy result = HttpWaitStrategy.builder().port(80).path("/").build();
        assertThat(result).isNotNull();
    }

    @Test
    void directConstructionHttpsWaitStrategy() {
        HttpWaitStrategy result = HttpWaitStrategy.builder()
                .port(443)
                .path("/")
                .protocol(Protocol.HTTPS)
                .build();
        assertThat(result).isNotNull();
    }

    @Test
    void directConstructionRejectsInvalidPortHttp() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpWaitStrategy.builder().port(0).path("/").build())
                .withMessageContaining("port must be set");
    }

    @Test
    void directConstructionRejectsNullPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpWaitStrategy.builder().port(80).path(null).build())
                .withMessageContaining("path must not be blank");
    }

    @Test
    void directConstructionRejectsBlankPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpWaitStrategy.builder().port(80).path("").build())
                .withMessageContaining("path must not be blank");
    }

    @Test
    void directConstructionRejectsMinStatusOutOfRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpWaitStrategy.builder()
                        .port(80)
                        .path("/")
                        .statusRange(99, 399)
                        .build())
                .withMessageContaining("minStatus must be in 100..599");
    }

    @Test
    void directConstructionRejectsMaxStatusOutOfRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpWaitStrategy.builder()
                        .port(80)
                        .path("/")
                        .statusRange(200, 600)
                        .build())
                .withMessageContaining("maxStatus must be in 100..599");
    }

    @Test
    void directConstructionRejectsMinGreaterThanMax() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpWaitStrategy.builder()
                        .port(80)
                        .path("/")
                        .statusRange(300, 200)
                        .build())
                .withMessageContaining("minStatus must be <= maxStatus");
    }

    private static WaitStrategy alwaysTrue() {
        return container -> true;
    }

    private static WaitStrategy alwaysFalse() {
        return container -> false;
    }

    private static WaitStrategy logObserving(Consumer<String> lineConsumer) {
        return new WaitStrategy() {
            @Override
            public boolean check(Container c) {
                return true;
            }

            @Override
            public Consumer<String> logLineConsumer() {
                return lineConsumer;
            }
        };
    }

    // === Wait factory null argument tests ===

    @Test
    void rejectsNullAllOfArray() {
        assertThatNullPointerException().isThrownBy(() -> Wait.allOf((WaitStrategy[]) null));
    }

    @Test
    void rejectsNullElementInAllOf() {
        assertThatNullPointerException().isThrownBy(() -> Wait.allOf(c -> true, null, c -> false));
    }

    @Test
    void rejectsNullAnyOfArray() {
        assertThatNullPointerException().isThrownBy(() -> Wait.anyOf((WaitStrategy[]) null));
    }

    @Test
    void rejectsNullElementInAnyOf() {
        assertThatNullPointerException().isThrownBy(() -> Wait.anyOf(c -> true, null));
    }

    // === Direct constructor validation tests ===

    @Test
    void portWaitStrategyDirectConstructorRejectsZeroPort() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PortWaitStrategy(0));
    }

    @Test
    void portWaitStrategyDirectConstructorRejectsTooHighPort() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PortWaitStrategy(65536));
    }

    @Test
    void portWaitStrategyDirectConstructorRejectsNegativePort() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PortWaitStrategy(-1));
    }

    @Test
    void logWaitStrategyDirectConstructorRejectsNullRegex() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LogWaitStrategy(null, 1))
                .withMessageContaining("regex must not be blank");
    }

    @Test
    void logWaitStrategyDirectConstructorRejectsBlankRegex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LogWaitStrategy("", 1));
    }

    @Test
    void logWaitStrategyDirectConstructorRejectsZeroTimes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LogWaitStrategy("x", 0))
                .withMessageContaining("times must be >= 1");
    }

    @Test
    void logWaitStrategyDirectConstructorRejectsNegativeTimes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LogWaitStrategy("x", -1));
    }

    @Test
    void httpWaitStrategyDirectConstructorRejectsNullProtocol() {
        assertThatNullPointerException().isThrownBy(() -> new HttpWaitStrategy(80, "/", null, 200, 399));
    }

    @Test
    void httpWaitStrategyBuilderRejectsNullProtocol() {
        assertThatNullPointerException()
                .isThrownBy(() -> HttpWaitStrategy.builder()
                        .port(80)
                        .path("/")
                        .protocol(null)
                        .build())
                .withMessage("protocol");
    }
}
