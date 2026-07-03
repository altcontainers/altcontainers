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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import nonapi.org.altcontainers.ContainerCreateSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-level OkHttp Docker Engine API client.
 *
 * <p>Manages two OkHttp clients: one for ordinary JSON requests with bounded timeouts and one for
 * streaming requests (pull, logs) with no read timeout. Both clients are immutable and thread-safe.
 */
public final class DockerHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(DockerHttpClient.class);

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final long CONNECT_TIMEOUT_SECONDS = 10;
    private static final long READ_TIMEOUT_SECONDS = 30;
    private static final long CALL_TIMEOUT_SECONDS = 60;

    private final DockerEndpoint endpoint;
    private final OkHttpClient jsonClient;
    private final OkHttpClient streamingClient;
    private final Gson gson;
    private final DockerJson dockerJson;

    /**
     * Creates a client for the given endpoint.
     *
     * <p>Builds OkHttp clients with appropriate timeouts. For Unix endpoints, configures a
     * custom socket factory.
     *
     * @param endpoint the resolved Docker endpoint; must not be {@code null}
     * @return the engine client
     */
    public static DockerHttpClient create(DockerEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");

        OkHttpClient.Builder jsonBuilder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY);

        OkHttpClient.Builder streamBuilder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for streaming
                .callTimeout(0, TimeUnit.MILLISECONDS) // no call timeout for long pulls
                .proxy(Proxy.NO_PROXY);

        if (endpoint.unixSocket()) {
            JdkUnixSocketFactory socketFactory = new JdkUnixSocketFactory(endpoint.unixSocketPath());
            okhttp3.Dns dns = hostname -> {
                if ("docker".equals(hostname)) {
                    return List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
                }
                return okhttp3.Dns.SYSTEM.lookup(hostname);
            };
            jsonBuilder.socketFactory(socketFactory).dns(dns);
            streamBuilder.socketFactory(socketFactory).dns(dns);
        }

        Gson gson = new GsonBuilder().create();
        return new DockerHttpClient(endpoint, jsonBuilder.build(), streamBuilder.build(), gson);
    }

    /**
     * Creates a client from explicit components for testing.
     *
     * @param endpoint the Docker endpoint
     * @param jsonClient the OkHttp client for JSON requests
     * @param streamingClient the OkHttp client for streaming requests
     * @param gson the Gson instance
     */
    public DockerHttpClient(DockerEndpoint endpoint, OkHttpClient jsonClient, OkHttpClient streamingClient, Gson gson) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.jsonClient = Objects.requireNonNull(jsonClient, "jsonClient must not be null");
        this.streamingClient = Objects.requireNonNull(streamingClient, "streamingClient must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
        this.dockerJson = new DockerJson(gson);
    }

    // --- Ping ---

    /**
     * Pings the Docker daemon.
     *
     * @throws DockerRequestException if the daemon does not respond with 2xx
     */
    public void ping() {
        Request request = new Request.Builder().url(buildUrl("_ping")).get().build();
        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", "/_ping");
        } catch (IOException e) {
            throw new DockerRequestException("Failed to ping Docker daemon", e);
        }
    }

    // --- Image operations ---

    /**
     * Inspects an image.
     *
     * @param image the image reference; must not be {@code null} or blank
     * @throws DockerNotFoundException if the image is not found
     * @throws DockerRequestException for other failures
     */
    public void inspectImage(String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        String path = "/images/" + encodePathSegment(image) + "/json";
        Request request = new Request.Builder().url(buildUrl(path)).get().build();
        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", path);
        } catch (IOException e) {
            throw new DockerRequestException("GET " + path + " failed", e);
        }
    }

    /**
     * Pulls a Docker image, consuming the progress stream.
     *
     * @param image the image reference; must not be {@code null} or blank
     * @throws DockerRequestException if the pull fails
     */
    public void pullImage(String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        ImageReference ref = ImageReference.parse(image);
        okhttp3.HttpUrl.Builder urlBuilder =
                buildUrl("/images/create").newBuilder().addQueryParameter("fromImage", ref.fromImage());
        if (ref.tag() != null) {
            urlBuilder.addQueryParameter("tag", ref.tag());
        }
        String url = urlBuilder.build().toString();

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(new byte[0], null))
                .build();

        try (Response response = streamingClient.newCall(request).execute()) {
            checkSuccess(response, "POST", "/images/create");
            ResponseBody body = response.body();
            if (body == null) {
                throw new DockerRequestException("POST /images/create returned no response body");
            }
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonObject progress = gson.fromJson(line, JsonObject.class);
                        if (progress.has("error") && !progress.get("error").isJsonNull()) {
                            String error = progress.get("error").getAsString();
                            if (!error.isBlank()) {
                                throw new DockerRequestException(error);
                            }
                        }
                    } catch (com.google.gson.JsonSyntaxException ignored) {
                        // Non-JSON progress lines are ignored.
                    }
                }
            }
        } catch (DockerRequestException | DockerNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("POST /images/create failed", e);
        } catch (RuntimeException e) {
            throw new DockerRequestException("Failed to pull image: " + image, e);
        }
    }

    // --- Container operations ---

    /**
     * Creates a container.
     *
     * @param spec the container specification; must not be {@code null}
     * @return the container ID
     * @throws DockerRequestException if creation fails
     */
    public String createContainer(ContainerCreateSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        String path = "/containers/create";
        JsonObject requestBody = dockerJson.containerCreateRequest(spec);
        Request request = new Request.Builder()
                .url(buildUrl(path))
                .post(RequestBody.create(gson.toJson(requestBody), JSON_MEDIA_TYPE))
                .build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "POST", path);
            JsonObject json = parseResponseBody(response);
            String containerId = dockerJson.requireString(json, "Id", "container create response");
            return containerId;
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("POST /containers/create failed", e);
        }
    }

    /**
     * Starts a container.
     *
     * @param id the container ID; must not be {@code null} or blank
     * @throws DockerRequestException if the start fails
     */
    public void startContainer(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/containers/" + encodePathSegment(id) + "/start";
        Request request = new Request.Builder()
                .url(buildUrl(path))
                .post(RequestBody.create(new byte[0], null))
                .build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "POST", path);
        } catch (IOException e) {
            throw new DockerRequestException("POST " + path + " failed", e);
        }
    }

    /**
     * Inspects a container.
     *
     * @param id the container ID; must not be {@code null} or blank
     * @return the container inspect result
     * @throws DockerNotFoundException if the container is not found
     * @throws DockerRequestException for other failures
     */
    public DockerContainerInspect inspectContainer(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/containers/" + encodePathSegment(id) + "/json";
        Request request = new Request.Builder().url(buildUrl(path)).get().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", path);
            JsonObject json = parseResponseBody(response);
            return dockerJson.containerInspect(json);
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("GET " + path + " failed", e);
        }
    }

    /**
     * Stops a container.
     *
     * @param id the container ID; must not be {@code null} or blank
     * @param timeoutSeconds the stop timeout in seconds
     * @throws DockerRequestException if the stop fails
     */
    public void stopContainer(String id, int timeoutSeconds) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/containers/" + encodePathSegment(id) + "/stop";
        okhttp3.HttpUrl url = buildUrl(path)
                .newBuilder()
                .addQueryParameter("t", String.valueOf(timeoutSeconds))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(new byte[0], null))
                .build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "POST", path);
        } catch (IOException e) {
            throw new DockerRequestException("POST " + path + " failed", e);
        }
    }

    /**
     * Removes a container.
     *
     * @param id the container ID; must not be {@code null} or blank
     * @param force whether to force-remove
     * @throws DockerRequestException if removal fails
     */
    public void removeContainer(String id, boolean force) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/containers/" + encodePathSegment(id);
        okhttp3.HttpUrl.Builder urlBuilder = buildUrl(path).newBuilder();
        if (force) {
            urlBuilder.addQueryParameter("force", "1");
        }

        Request request = new Request.Builder().url(urlBuilder.build()).delete().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "DELETE", path);
        } catch (IOException e) {
            throw new DockerRequestException("DELETE " + path + " failed", e);
        }
    }

    // --- Network operations ---

    /**
     * Creates a bridge network.
     *
     * @param name the network name; must not be {@code null} or blank
     * @param labels the network labels; must not be {@code null}
     * @return the network ID
     * @throws DockerRequestException if creation fails
     */
    public String createNetwork(String name, Map<String, String> labels) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(labels, "labels must not be null");
        String path = "/networks/create";
        JsonObject requestBody = dockerJson.networkCreateRequest(name, labels);

        Request request = new Request.Builder()
                .url(buildUrl(path))
                .post(RequestBody.create(gson.toJson(requestBody), JSON_MEDIA_TYPE))
                .build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "POST", path);
            JsonObject json = parseResponseBody(response);
            return dockerJson.requireString(json, "Id", "network create response");
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("POST /networks/create failed", e);
        }
    }

    /**
     * Inspects a network.
     *
     * @param id the network ID; must not be {@code null} or blank
     * @return the network inspect result
     * @throws DockerNotFoundException if the network is not found
     */
    public DockerNetworkInspect inspectNetwork(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/networks/" + encodePathSegment(id);
        Request request = new Request.Builder().url(buildUrl(path)).get().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", path);
            JsonObject json = parseResponseBody(response);
            return dockerJson.networkInspect(json);
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("GET " + path + " failed", e);
        }
    }

    /**
     * Lists container IDs matching the given labels.
     *
     * @param labels the label filter map
     * @return the matching container IDs
     * @throws DockerRequestException if the list operation fails
     */
    public List<String> listContainerIdsByLabels(Map<String, String> labels) {
        String filtersJson = dockerJson.labelFiltersJson(labels);
        okhttp3.HttpUrl url = buildUrl("/containers/json")
                .newBuilder()
                .addQueryParameter("all", "1")
                .addQueryParameter("filters", filtersJson)
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", "/containers/json");
            JsonArray array = parseResponseArray(response);
            return dockerJson.idsFromArray(array);
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("GET /containers/json failed", e);
        }
    }

    /**
     * Lists network IDs matching the given labels.
     *
     * @param labels the label filter map
     * @return the matching network IDs
     * @throws DockerRequestException if the list operation fails
     */
    public List<String> listNetworkIdsByLabels(Map<String, String> labels) {
        String filtersJson = dockerJson.labelFiltersJson(labels);
        okhttp3.HttpUrl url = buildUrl("/networks")
                .newBuilder()
                .addQueryParameter("filters", filtersJson)
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", "/networks");
            JsonArray array = parseResponseArray(response);
            return dockerJson.idsFromArray(array);
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("GET /networks failed", e);
        }
    }

    /**
     * Removes a network.
     *
     * @param id the network ID; must not be {@code null} or blank
     * @throws DockerRequestException if removal fails
     */
    public void removeNetwork(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/networks/" + encodePathSegment(id);
        Request request = new Request.Builder().url(buildUrl(path)).delete().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "DELETE", path);
        } catch (IOException e) {
            throw new DockerRequestException("DELETE " + path + " failed", e);
        }
    }

    // --- Log operations ---

    /**
     * Tails a container's stdout/stderr logs.
     *
     * @param id the container ID; must not be {@code null} or blank
     * @param payloadConsumer receives log frame payloads
     * @return a closeable that cancels the tail stream
     * @throws DockerRequestException if the log tail fails to start
     */
    public Closeable tailLogs(String id, Consumer<byte[]> payloadConsumer) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(payloadConsumer, "payloadConsumer must not be null");
        String path = "/containers/" + encodePathSegment(id) + "/logs";
        okhttp3.HttpUrl url = buildUrl(path)
                .newBuilder()
                .addQueryParameter("stdout", "1")
                .addQueryParameter("stderr", "1")
                .addQueryParameter("follow", "1")
                .addQueryParameter("tail", "all")
                .build();

        Request request = new Request.Builder().url(url).get().build();
        okhttp3.Call call = streamingClient.newCall(request);
        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            throw new DockerRequestException("GET " + path + " failed", e);
        }
        try {
            checkSuccess(response, "GET", path);
        } catch (RuntimeException e) {
            try {
                response.close();
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } catch (IOException e) {
            try {
                response.close();
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new DockerRequestException("GET " + path + " failed", e);
        }

        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new DockerRequestException("GET " + path + " returned no body");
        }

        // Start daemon thread to read and parse log frames.
        DockerLogFrameParser parser = new DockerLogFrameParser();
        Thread readerThread = new Thread(
                () -> {
                    try (response;
                            var inputStream = body.byteStream()) {
                        parser.parse(inputStream, payload -> {
                            try {
                                payloadConsumer.accept(payload);
                            } catch (RuntimeException e) {
                                // Consumer failure must not kill the log-stream daemon thread,
                                // but should be logged for diagnostics.
                                logger.warn("Log payload consumer threw exception", e);
                            }
                        });
                    } catch (IOException e) {
                        // Stream ended or was closed; normal teardown.
                    }
                },
                "docker-logs-" + id);
        readerThread.setDaemon(true);
        try {
            readerThread.start();
        } catch (RuntimeException e) {
            response.close();
            call.cancel();
            throw e;
        }

        // Return a closeable that cancels the call and closes the response.
        return () -> {
            call.cancel();
            try {
                readerThread.interrupt();
            } catch (RuntimeException ignored) {
                // Best-effort.
            }
        };
    }

    /**
     * Retrieves a snapshot of container stdout/stderr logs (non-following).
     *
     * @param id the container ID; must not be {@code null} or blank
     * @return the combined log text
     * @throws DockerRequestException if the log retrieval fails
     */
    public String getContainerLogs(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        String path = "/containers/" + encodePathSegment(id) + "/logs";
        okhttp3.HttpUrl url = buildUrl(path)
                .newBuilder()
                .addQueryParameter("stdout", "1")
                .addQueryParameter("stderr", "1")
                .addQueryParameter("tail", "all")
                .build();

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "GET", path);
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            DockerLogFrameParser parser = new DockerLogFrameParser();
            parser.parse(body.byteStream(), payload -> result.append(new String(payload, StandardCharsets.UTF_8)));
            return result.toString();
        } catch (DockerNotFoundException | DockerRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new DockerRequestException("GET " + path + " failed", e);
        } catch (RuntimeException e) {
            throw new DockerRequestException("Failed to get container logs: " + id, e);
        }
    }

    /**
     * Copies a file into a container by writing a tar archive to
     * {@code PUT /containers/{id}/archive}.
     *
     * @param id the container identifier; must not be blank
     * @param containerPath the destination directory inside the container
     * @param fileName the file name
     * @param content the file content
     * @param mode the file mode (e.g., 0777 for executable)
     * @throws DockerRequestException if the copy fails
     */
    public void putArchive(String id, String containerPath, String fileName, byte[] content, int mode) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (containerPath == null || containerPath.isBlank()) {
            throw new IllegalArgumentException("containerPath must not be blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (mode < 0) {
            throw new IllegalArgumentException("mode must be >= 0, was " + mode);
        }
        byte[] tar = createMinimalTar(fileName, content, mode);
        String path = "/containers/" + encodePathSegment(id) + "/archive";
        okhttp3.HttpUrl url = buildUrl(path)
                .newBuilder()
                .addQueryParameter("path", containerPath)
                .build();
        okhttp3.MediaType tarMediaType = okhttp3.MediaType.get("application/x-tar");
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(tar, tarMediaType))
                .build();
        try (Response response = jsonClient.newCall(request).execute()) {
            checkSuccess(response, "PUT", path);
        } catch (IOException e) {
            throw new DockerRequestException("PUT " + path + " failed", e);
        }
    }

    /**
     * Maximum length, in UTF-8 bytes, of a TAR entry name in the minimal single-entry archive.
     *
     * <p>The ustar name field is 100 bytes; longer names cannot be represented without GNU/POSIX
     * long-name extension headers, which this minimal archive does not produce.
     */
    static final int MAX_TAR_NAME_BYTES = 100;

    /**
     * Maximum content size, in bytes, accepted by {@link #createMinimalTar}.
     * Derived from the maximum array allocation that fits in {@code int}:
     * the block-padding arithmetic must not overflow, so the content length is
     * rounded down to the nearest multiple of the TAR block size (512 bytes)
     * below {@code Integer.MAX_VALUE - 1536}.
     */
    static final int MAX_TAR_CONTENT_SIZE = (Integer.MAX_VALUE - 1536) / 512 * 512;

    /**
     * Creates a minimal POSIX tar archive with a single file.
     *
     * @param name the entry name; its UTF-8 encoding must not exceed {@value #MAX_TAR_NAME_BYTES} bytes
     * @param content the file content; must not exceed {@value #MAX_TAR_CONTENT_SIZE} bytes
     * @param mode the file mode (e.g., {@code 0644})
     * @return the TAR archive bytes
     * @throws IllegalArgumentException if {@code name} exceeds {@value #MAX_TAR_NAME_BYTES} UTF-8 bytes,
     *     or {@code content} exceeds {@value #MAX_TAR_CONTENT_SIZE} bytes, or {@code mode} is negative
     */
    static byte[] createMinimalTar(String name, byte[] content, int mode) {
        if (mode < 0) {
            throw new IllegalArgumentException("mode must be >= 0, was " + mode);
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        requireValidTarName(nameBytes);
        requireValidContentSize(content.length);
        int blockSize = 512;
        int contentBlocks = (content.length + blockSize - 1) / blockSize;
        int contentPadded = contentBlocks * blockSize;
        byte[] tar = new byte[blockSize + contentPadded + blockSize * 2];
        writeTarHeader(tar, 0, name, content.length, mode);
        System.arraycopy(content, 0, tar, blockSize, content.length);
        return tar;
    }

    /**
     * Validates that a TAR entry name fits the 100-byte ustar name field.
     *
     * @param nameBytes the UTF-8 encoded name
     * @throws IllegalArgumentException if the name exceeds {@value #MAX_TAR_NAME_BYTES} bytes
     */
    private static void requireValidTarName(byte[] nameBytes) {
        if (nameBytes.length > MAX_TAR_NAME_BYTES) {
            throw new IllegalArgumentException("fileName too long for TAR entry: " + nameBytes.length
                    + " UTF-8 bytes (max: " + MAX_TAR_NAME_BYTES + ")");
        }
    }

    /**
     * Validates that {@code contentLength} can be stored in a minimal TAR archive without {@code int}
     * overflow in the block-padding arithmetic.
     *
     * @param contentLength the proposed content length in bytes
     * @throws IllegalArgumentException if the length exceeds {@value #MAX_TAR_CONTENT_SIZE} bytes
     */
    static void requireValidContentSize(int contentLength) {
        if (contentLength > MAX_TAR_CONTENT_SIZE) {
            throw new IllegalArgumentException("content too large for TAR archive: " + contentLength + " bytes (max: "
                    + MAX_TAR_CONTENT_SIZE + ")");
        }
    }

    private static void writeTarHeader(byte[] buf, int off, String name, int size, int mode) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, buf, off, Math.min(nameBytes.length, 100));
        writeOctalField(buf, off + 100, 8, mode);
        writeOctalField(buf, off + 108, 8, 0); // uid
        writeOctalField(buf, off + 116, 8, 0); // gid
        writeOctalField(buf, off + 124, 12, size);
        writeOctalField(buf, off + 136, 12, System.currentTimeMillis() / 1000); // mtime
        // Blank checksum field before computing
        for (int i = off + 148; i < off + 156; i++) {
            buf[i] = ' ';
        }
        buf[off + 156] = '0'; // typeflag: regular file
        // ustar magic
        byte[] magic = "ustar".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(magic, 0, buf, off + 257, magic.length);
        buf[off + 263] = '0';
        buf[off + 264] = '0';
        // Compute checksum: sum of all 512 header bytes, treating checksum field as blanks
        long checksum = 0;
        for (int i = off; i < off + 512; i++) {
            checksum += buf[i] < 0 ? buf[i] + 256 : buf[i];
        }
        writeOctalField(buf, off + 148, 7, checksum);
        buf[off + 155] = ' ';
    }

    /**
     * Writes an octal number right-justified into a fixed-width field, terminated by a NUL byte.
     *
     * @param buf the target buffer
     * @param off start offset of the field
     * @param fieldLen total field length in bytes (including NUL terminator)
     * @param value the value to write in octal
     */
    private static void writeOctalField(byte[] buf, int off, int fieldLen, long value) {
        int end = off + fieldLen - 1;
        if (value == 0) {
            for (int i = off; i < end; i++) {
                buf[i] = '0';
            }
        } else {
            int pos = end - 1;
            long v = value;
            while (v > 0 && pos >= off) {
                buf[pos--] = (byte) ('0' + (v & 7));
                v >>>= 3;
            }
            while (pos >= off) {
                buf[pos--] = '0';
            }
        }
        buf[end] = 0; // NUL terminator
    }

    // --- Internal helpers ---

    private okhttp3.HttpUrl buildUrl(String path) {
        return endpoint.baseUrl().resolve(path);
    }

    /**
     * URL-encodes a path segment, preserving forward slashes since Docker image names may contain
     * them and must be treated as a single path segment before the final component.
     */
    private static String encodePathSegment(String segment) {
        // URL-encode each segment, keeping '/' for Docker image names like library/nginx.
        // Iterates over Unicode code points, not Java char values, to correctly
        // encode characters outside the BMP (surrogate pairs).
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segment.length(); ) {
            int cp = segment.codePointAt(i);
            if (cp == '/') {
                encoded.append('/');
            } else if (cp < 0x80 && isUnreserved((char) cp)) {
                encoded.append((char) cp);
            } else {
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : utf8) {
                    encoded.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
            i += Character.charCount(cp);
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '.'
                || c == '_'
                || c == '~';
    }

    private static void checkSuccess(Response response, String method, String path) throws IOException {
        if (!response.isSuccessful()) {
            if (response.code() == 404) {
                throw new DockerNotFoundException(method, path);
            }
            String snippet = "";
            ResponseBody body = response.body();
            if (body != null) {
                try {
                    String bodyStr = body.string();
                    if (bodyStr.length() > 200) {
                        bodyStr = bodyStr.substring(0, 200);
                    }
                    snippet = bodyStr;
                } catch (IOException ignored) {
                    // Best-effort.
                }
            }
            throw new DockerRequestException(method, path, response.code(), snippet);
        }
    }

    private JsonObject parseResponseBody(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new DockerRequestException(
                    response.request().method() + " " + response.request().url().encodedPath() + " returned no body");
        }
        String bodyStr = body.string();
        try {
            return gson.fromJson(bodyStr, JsonObject.class);
        } catch (RuntimeException e) {
            throw new DockerRequestException(
                    response.request().method() + " " + response.request().url().encodedPath()
                            + " returned invalid JSON",
                    e);
        }
    }

    private JsonArray parseResponseArray(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new DockerRequestException(
                    response.request().method() + " " + response.request().url().encodedPath() + " returned no body");
        }
        String bodyStr = body.string();
        JsonArray array;
        try {
            array = gson.fromJson(bodyStr, JsonArray.class);
        } catch (RuntimeException e) {
            throw new DockerRequestException(
                    response.request().method() + " " + response.request().url().encodedPath()
                            + " returned invalid JSON",
                    e);
        }
        if (array == null) {
            throw new DockerRequestException(
                    response.request().method() + " " + response.request().url().encodedPath() + " returned no body");
        }
        return array;
    }
}
