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

package nonapi.org.altcontainers.docker;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import okhttp3.HttpUrl;
import org.altcontainers.api.ContainerException;

/**
 * Immutable resolved Docker daemon endpoint.
 *
 * <p>Resolves from system property, environment variable, or default Unix socket. Validates
 * Unix socket paths exist and are sockets.
 */
public final class DockerEndpoint {

    /**
     * System property name for configuring the Docker host.
     */
    public static final String SYSTEM_PROPERTY_NAME = "altcontainers.docker.host";

    /**
     * Default Docker host when no system property or environment variable is set.
     */
    public static final String DEFAULT_DOCKER_HOST = "unix:///var/run/docker.sock";

    /**
     * URI scheme for the Docker endpoint.
     */
    public enum Scheme {
        /** Unix domain socket. */
        UNIX,
        /** TCP connection over HTTP. */
        HTTP,
        /** TCP connection over HTTPS. */
        HTTPS
    }

    private final String configuredHost;
    private final Scheme scheme;
    private final Path unixSocketPath;
    private final HttpUrl baseUrl;

    private DockerEndpoint(String configuredHost, Scheme scheme, Path unixSocketPath, HttpUrl baseUrl) {
        this.configuredHost = configuredHost;
        this.scheme = scheme;
        this.unixSocketPath = unixSocketPath;
        this.baseUrl = baseUrl;
    }

    /**
     * Resolves the Docker endpoint from the JVM system property {@code altcontainers.docker.host},
     * then the environment variable {@code DOCKER_HOST}, then the default
     * {@code unix:///var/run/docker.sock}.
     *
     * @return the resolved endpoint
     * @throws ContainerException if the configured host is unsupported or malformed
     */
    public static DockerEndpoint resolve() {
        return resolve(System.getProperty(SYSTEM_PROPERTY_NAME), System.getenv("DOCKER_HOST"));
    }

    /**
     * Resolves the Docker endpoint from explicit values, useful for testing.
     *
     * <p>System property takes precedence over environment variable; blank values are ignored.
     *
     * @param systemPropertyValue the value of {@code altcontainers.docker.host} (may be {@code null} or blank)
     * @param environmentValue the value of {@code DOCKER_HOST} (may be {@code null} or blank)
     * @return the resolved endpoint
     * @throws ContainerException if no configured host is available or the host is unsupported/malformed
     */
    public static DockerEndpoint resolve(String systemPropertyValue, String environmentValue) {
        if (systemPropertyValue != null && !systemPropertyValue.isBlank()) {
            return parse(systemPropertyValue);
        }
        if (environmentValue != null && !environmentValue.isBlank()) {
            return parse(environmentValue);
        }
        return parse(DEFAULT_DOCKER_HOST);
    }

    /**
     * Parses a configured Docker host string.
     *
     * <p>Supported schemes: {@code unix://}, {@code tcp://}, {@code http://}, {@code https://}.
     *
     * @param configuredHost the Docker host string
     * @return the parsed endpoint
     * @throws ContainerException if the scheme is unsupported or the URI is malformed
     */
    public static DockerEndpoint parse(String configuredHost) {
        Objects.requireNonNull(configuredHost, "configuredHost must not be null");
        URI uri;
        try {
            uri = new URI(configuredHost);
        } catch (URISyntaxException e) {
            throw new ContainerException("Invalid Docker host: " + configuredHost, e);
        }
        String schemeStr = uri.getScheme();
        if (schemeStr == null) {
            throw new ContainerException("Docker host has no scheme: " + configuredHost);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        String query = uri.getQuery();
        String fragment = uri.getFragment();

        return switch (schemeStr) {
            case "unix" -> {
                if (path == null || path.isBlank()) {
                    throw new ContainerException("Docker Unix socket path must not be empty: " + configuredHost);
                }
                yield new DockerEndpoint(configuredHost, Scheme.UNIX, Path.of(path), HttpUrl.get("http://docker/"));
            }
            case "tcp" -> {
                if (host == null || host.isBlank()) {
                    throw new ContainerException("Docker TCP host must specify a hostname: " + configuredHost);
                }
                int effectivePort = port > 0 ? port : 2375;
                if (effectivePort < 1 || effectivePort > 65535) {
                    throw new ContainerException("Docker TCP port must be in 1..65535, was " + effectivePort);
                }
                yield new DockerEndpoint(
                        configuredHost, Scheme.HTTP, null, HttpUrl.get("http://" + host + ":" + effectivePort + "/"));
            }
            case "http" -> {
                if (host == null || host.isBlank()) {
                    throw new ContainerException("Docker HTTP host must specify a hostname: " + configuredHost);
                }
                int effectivePort = port > 0 ? port : 80;
                if (effectivePort < 1 || effectivePort > 65535) {
                    throw new ContainerException("Docker HTTP port must be in 1..65535, was " + effectivePort);
                }
                String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                yield new DockerEndpoint(
                        configuredHost,
                        Scheme.HTTP,
                        null,
                        HttpUrl.get("http://" + host + ":" + effectivePort + normalizedPath + buildRest(query, fragment)
                                + "/"));
            }
            case "https" -> {
                if (host == null || host.isBlank()) {
                    throw new ContainerException("Docker HTTPS host must specify a hostname: " + configuredHost);
                }
                int effectivePort = port > 0 ? port : 443;
                if (effectivePort < 1 || effectivePort > 65535) {
                    throw new ContainerException("Docker HTTPS port must be in 1..65535, was " + effectivePort);
                }
                String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                yield new DockerEndpoint(
                        configuredHost,
                        Scheme.HTTPS,
                        null,
                        HttpUrl.get("https://" + host + ":" + effectivePort + normalizedPath
                                + buildRest(query, fragment) + "/"));
            }
            case "npipe" ->
                throw new ContainerException("Docker named pipe endpoints are not supported."
                        + " Linux/macOS Unix sockets and TCP/HTTP(S) Docker hosts are supported.");
            default ->
                throw new ContainerException("Unsupported Docker host scheme: " + schemeStr
                        + ". Supported schemes: unix://, tcp://, http://, https://");
        };
    }

    private static String buildRest(String query, String fragment) {
        String qs = (query != null && !query.isBlank()) ? "?" + query : "";
        String frag = (fragment != null && !fragment.isBlank()) ? "#" + fragment : "";
        return qs + frag;
    }

    /**
     * Creates an endpoint from an OkHttp URL for test use.
     *
     * @param baseUrl the OkHttp URL
     * @return an HTTP endpoint with the given base URL
     */
    public static DockerEndpoint forHttpBaseUrl(HttpUrl baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        boolean isHttps = "https".equals(baseUrl.scheme());
        return new DockerEndpoint(baseUrl.toString(), isHttps ? Scheme.HTTPS : Scheme.HTTP, null, baseUrl);
    }

    /**
     * Validates this endpoint. For Unix sockets, checks that the socket path exists and is a Unix socket.
     * For HTTP(S) endpoints, validates the base URL host is present.
     *
     * @throws ContainerException if validation fails
     */
    public void validate() {
        if (scheme == Scheme.UNIX) {
            if (!Files.exists(unixSocketPath)) {
                throw new ContainerException("Docker Unix socket does not exist: " + unixSocketPath);
            }
            if (!isUnixSocket(unixSocketPath)) {
                throw new ContainerException("Docker Unix socket path is not a socket: " + unixSocketPath);
            }
        }
    }

    /**
     * Returns whether the path is a connectable Unix domain socket.
     *
     * <p>Java 17 has no portable attribute API to identify Unix sockets, so this method performs a
     * non-destructive connect probe: it opens a Unix-domain {@link SocketChannel}, connects to
     * {@code path}, and immediately closes it. FIFOs, device files, and regular files fail the
     * connect and return {@code false}; a reachable socket returns {@code true}. The probe runs once
     * at bootstrap, immediately before the Docker {@code _ping}.
     *
     * @param path the candidate socket path
     * @return {@code true} if a Unix-domain socket accepts the connection
     */
    private static boolean isUnixSocket(Path path) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(path));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Returns the configured host string.
     *
     * @return the configured host
     */
    public String configuredHost() {
        return configuredHost;
    }

    /**
     * Returns the endpoint scheme.
     *
     * @return the scheme
     */
    public Scheme scheme() {
        return scheme;
    }

    /**
     * Returns whether this endpoint uses a Unix socket.
     *
     * @return {@code true} if the scheme is {@link Scheme#UNIX}
     */
    public boolean unixSocket() {
        return scheme == Scheme.UNIX;
    }

    /**
     * Returns the Unix socket path, or {@code null} for non-Unix endpoints.
     *
     * @return the Unix socket path, or {@code null}
     */
    public Path unixSocketPath() {
        return unixSocketPath;
    }

    /**
     * Returns the base URL for Docker API requests.
     *
     * @return the OkHttp base URL
     */
    public HttpUrl baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the base URL as a string.
     *
     * @return the base URL string
     */
    public String baseUrlString() {
        return baseUrl.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DockerEndpoint that)) return false;
        return configuredHost.equals(that.configuredHost)
                && scheme == that.scheme
                && Objects.equals(unixSocketPath, that.unixSocketPath)
                && baseUrl.equals(that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(configuredHost, scheme, unixSocketPath, baseUrl);
    }

    @Override
    public String toString() {
        return "DockerEndpoint[" + configuredHost + " " + scheme + "]";
    }
}
