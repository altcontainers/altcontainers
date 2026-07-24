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

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import nonapi.org.altcontainers.api.AltcontainersProperties;
import nonapi.org.altcontainers.api.ConcreteContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for HttpWaitStrategy, including programmatic timeout tuning
 * and HTTPS self-signed certificate handling.
 */
class HttpWaitStrategyTest {

    private static final String KEYSTORE_PASSWORD = "password";
    private static Path keystorePath;

    @BeforeAll
    static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("test-keystore", ".p12");
        Files.delete(keystorePath);
        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystorePath.toString(),
                "-storepass",
                KEYSTORE_PASSWORD,
                "-keypass",
                KEYSTORE_PASSWORD,
                "-dname",
                "CN=localhost",
                "-validity",
                "365",
                "-ext",
                "SAN=DNS:localhost",
                "-noprompt");
        int exitCode = pb.inheritIO().start().waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool exited with code " + exitCode);
        }
    }

    @AfterAll
    static void deleteKeystore() throws IOException {
        if (keystorePath != null) {
            Files.deleteIfExists(keystorePath);
        }
    }

    @BeforeEach
    void setUp() {
        System.clearProperty("altcontainers.wait.http.probe.timeout.ms");
        AltcontainersProperties.reset();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("altcontainers.wait.http.probe.timeout.ms");
        AltcontainersProperties.reset();
    }

    @Test
    void shouldUseDefaultTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpWaitStrategy strategy = new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTP, 8080, "/health", 200, 399);

            Container container =
                    new ConcreteContainer(
                            "test-id",
                            "test-image",
                            ContainerSpec.builder("test-image").build(),
                            null) {
                        @Override
                        public String host() {
                            return "localhost";
                        }

                        @Override
                        public Integer hostPort(int containerPort) {
                            return port;
                        }
                    };

            long start = System.currentTimeMillis();
            boolean result = strategy.check(container);
            long duration = System.currentTimeMillis() - start;

            assertThat(result).isFalse();
            // Default timeout is 2000ms — should complete around that time
            assertThat(duration).isLessThan(4000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldHonorSystemPropertyHttpProbeTimeout() throws Exception {
        System.setProperty("altcontainers.wait.http.probe.timeout.ms", "500");
        AltcontainersProperties.reset();

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpWaitStrategy strategy = new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTP, 8080, "/health", 200, 399);

            Container container =
                    new ConcreteContainer(
                            "test-id",
                            "test-image",
                            ContainerSpec.builder("test-image").build(),
                            null) {
                        @Override
                        public String host() {
                            return "localhost";
                        }

                        @Override
                        public Integer hostPort(int containerPort) {
                            return port;
                        }
                    };

            long start = System.currentTimeMillis();
            boolean result = strategy.check(container);
            long duration = System.currentTimeMillis() - start;

            assertThat(result).isFalse();
            // With 500ms timeout, should complete well under 2 seconds
            assertThat(duration).isLessThan(2000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnTrueForValidResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpWaitStrategy strategy = new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTP, 8080, "/health", 200, 399);

            Container container =
                    new ConcreteContainer(
                            "test-id",
                            "test-image",
                            ContainerSpec.builder("test-image").build(),
                            null) {
                        @Override
                        public String host() {
                            return "localhost";
                        }

                        @Override
                        public Integer hostPort(int containerPort) {
                            return port;
                        }
                    };

            boolean result = strategy.check(container);
            assertThat(result).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnTrueForSelfSignedHttpsWithInsecureProtocol() throws Exception {
        HttpsServer server = createSelfSignedHttpsServer();
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpWaitStrategy strategy =
                    new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTPS_INSECURE, 8080, "/health", 200, 399);

            Container container =
                    new ConcreteContainer(
                            "test-id",
                            "test-image",
                            ContainerSpec.builder("test-image").build(),
                            null) {
                        @Override
                        public String host() {
                            return "localhost";
                        }

                        @Override
                        public Integer hostPort(int containerPort) {
                            return port;
                        }
                    };

            boolean result = strategy.check(container);
            assertThat(result).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnFalseForSelfSignedHttpsWithVerifyProtocol() throws Exception {
        HttpsServer server = createSelfSignedHttpsServer();
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpWaitStrategy strategy =
                    new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTPS_VERIFY, 8080, "/health", 200, 399);

            Container container =
                    new ConcreteContainer(
                            "test-id",
                            "test-image",
                            ContainerSpec.builder("test-image").build(),
                            null) {
                        @Override
                        public String host() {
                            return "localhost";
                        }

                        @Override
                        public Integer hostPort(int containerPort) {
                            return port;
                        }
                    };

            boolean result = strategy.check(container);
            assertThat(result).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldShareHttpClientAcrossInstances() {
        HttpWaitStrategy first = new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTP, 8080, "/health", 200, 399);
        HttpWaitStrategy second = new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTP, 9090, "/ready", 200, 399);

        assertThat(first.httpClient()).isSameAs(second.httpClient());
    }

    private static HttpsServer createSelfSignedHttpsServer() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(Files.newInputStream(keystorePath), KEYSTORE_PASSWORD.toCharArray());

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return server;
    }
}
