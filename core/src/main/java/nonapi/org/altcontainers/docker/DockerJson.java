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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nonapi.org.altcontainers.ContainerCreateSpec;

/**
 * Gson-based Docker JSON request builders and response extractors.
 *
 * <p>This class is stateless and thread-safe. The Gson instance is passed in at construction and
 * used read-only.
 */
public final class DockerJson {

    private final Gson gson;

    /**
     * Creates a JSON helper with the given Gson instance.
     *
     * @param gson the Gson instance to use for serialization and deserialization; must not be
     *     {@code null}
     */
    public DockerJson(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
    }

    /**
     * Builds a container create request body from a {@link ContainerCreateSpec}.
     *
     * @param spec the container spec; must not be {@code null}
     * @return the request JSON object
     */
    public JsonObject containerCreateRequest(ContainerCreateSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        JsonObject root = new JsonObject();

        root.addProperty("Image", spec.image());

        // Cmd
        if (!spec.command().isEmpty()) {
            JsonArray cmd = new JsonArray();
            for (String c : spec.command()) {
                cmd.add(c);
            }
            root.add("Cmd", cmd);
        }

        // ExposedPorts
        if (!spec.exposedPorts().isEmpty()) {
            JsonObject exposedPorts = new JsonObject();
            for (int port : spec.exposedPorts()) {
                exposedPorts.add(port + "/tcp", new JsonObject());
            }
            root.add("ExposedPorts", exposedPorts);
        }

        // Labels
        if (!spec.labels().isEmpty()) {
            JsonObject labels = new JsonObject();
            for (var entry : spec.labels().entrySet()) {
                labels.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("Labels", labels);
        }

        // Env
        if (!spec.environment().isEmpty()) {
            JsonArray env = new JsonArray();
            for (var entry : spec.environment().entrySet()) {
                env.add(entry.getKey() + "=" + entry.getValue());
            }
            root.add("Env", env);
        }

        // WorkingDir
        if (spec.workingDirectory() != null) {
            root.addProperty("WorkingDir", spec.workingDirectory());
        }

        // HostConfig
        JsonObject hostConfig = new JsonObject();

        // Binds
        if (!spec.bindMounts().isEmpty()) {
            JsonArray binds = new JsonArray();
            for (var mount : spec.bindMounts()) {
                binds.add(mount.hostPath() + ":" + mount.containerPath() + ":rw");
            }
            hostConfig.add("Binds", binds);
        }

        // PortBindings
        if (!spec.exposedPorts().isEmpty()) {
            JsonObject portBindings = new JsonObject();
            for (int port : spec.exposedPorts()) {
                String portSpec = port + "/tcp";
                Integer fixed = spec.portBindings().get(port);
                JsonArray bindingArray = new JsonArray();
                JsonObject binding = new JsonObject();
                if (fixed != null) {
                    binding.addProperty("HostPort", String.valueOf(fixed));
                }
                bindingArray.add(binding);
                portBindings.add(portSpec, bindingArray);
            }
            hostConfig.add("PortBindings", portBindings);
        }

        // NetworkMode
        if (spec.networkMode() != null) {
            hostConfig.addProperty("NetworkMode", spec.networkMode());
        }

        // Resource limits
        if (spec.memory() > 0) {
            hostConfig.addProperty("Memory", spec.memory());
        }
        if (spec.memorySwap() > 0) {
            hostConfig.addProperty("MemorySwap", spec.memorySwap());
        }
        if (spec.shmSize() > 0) {
            hostConfig.addProperty("ShmSize", spec.shmSize());
        }
        if (spec.cpuShares() > 0) {
            hostConfig.addProperty("CpuShares", spec.cpuShares());
        }
        if (spec.cpuPeriod() > 0) {
            hostConfig.addProperty("CpuPeriod", spec.cpuPeriod());
        }
        if (spec.cpuQuota() > 0) {
            hostConfig.addProperty("CpuQuota", spec.cpuQuota());
        }
        if (!spec.ulimits().isEmpty()) {
            JsonArray ulimits = new JsonArray();
            for (var u : spec.ulimits()) {
                JsonObject ulimit = new JsonObject();
                ulimit.addProperty("Name", u.name());
                ulimit.addProperty("Soft", u.soft());
                ulimit.addProperty("Hard", u.hard());
                ulimits.add(ulimit);
            }
            hostConfig.add("Ulimits", ulimits);
        }

        root.add("HostConfig", hostConfig);

        // NetworkingConfig (aliases)
        if (spec.networkMode() != null && !spec.networkAliases().isEmpty()) {
            JsonObject networkingConfig = new JsonObject();
            JsonObject endpointsConfig = new JsonObject();
            JsonObject endpoint = new JsonObject();
            JsonArray aliases = new JsonArray();
            for (String alias : spec.networkAliases()) {
                aliases.add(alias);
            }
            endpoint.add("Aliases", aliases);
            endpointsConfig.add(spec.networkMode(), endpoint);
            networkingConfig.add("EndpointsConfig", endpointsConfig);
            root.add("NetworkingConfig", networkingConfig);
        }

        return root;
    }

    /**
     * Builds a network create request body.
     *
     * @param name the network name; must not be {@code null} or blank
     * @param labels the network labels; must not be {@code null}
     * @return the request JSON object
     */
    public JsonObject networkCreateRequest(String name, Map<String, String> labels) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(labels, "labels must not be null");
        JsonObject root = new JsonObject();
        root.addProperty("Name", name);
        root.addProperty("Driver", "bridge");
        if (!labels.isEmpty()) {
            JsonObject labelsJson = new JsonObject();
            for (var entry : labels.entrySet()) {
                labelsJson.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("Labels", labelsJson);
        }
        return root;
    }

    /**
     * Builds a Docker label filters JSON string for container and network listing.
     *
     * @param labels the label filter map; each entry becomes a {@code label=key=value} filter
     * @return the filters JSON string for query parameter use
     */
    public String labelFiltersJson(Map<String, String> labels) {
        JsonObject filters = new JsonObject();
        JsonArray labelArray = new JsonArray();
        for (var entry : labels.entrySet()) {
            labelArray.add(entry.getKey() + "=" + entry.getValue());
        }
        filters.add("label", labelArray);
        return gson.toJson(filters);
    }

    /**
     * Extracts {@link DockerContainerInspect} fields from an inspect response body.
     *
     * @param json the full inspect response JSON object
     * @return the extracted fields
     */
    public DockerContainerInspect containerInspect(JsonObject json) {
        JsonObject state = json.getAsJsonObject("State");
        boolean running = state != null && jsonBoolean(state, "Running", false);
        boolean oomKilled = state != null && jsonBoolean(state, "OOMKilled", false);
        boolean dead = state != null && jsonBoolean(state, "Dead", false);
        Integer exitCode = (state != null
                        && state.has("ExitCode")
                        && !state.get("ExitCode").isJsonNull())
                ? state.get("ExitCode").getAsInt()
                : null;
        String error = state != null ? jsonString(state, "Error", null) : null;

        Map<String, String> labels = extractStringMap(json, "Config", "Labels");
        Map<String, List<DockerPortBinding>> ports = extractPorts(json);

        return new DockerContainerInspect(running, oomKilled, dead, exitCode, error, labels, ports);
    }

    /**
     * Extracts {@link DockerNetworkInspect} fields from an inspect response body.
     *
     * @param json the full inspect response JSON object
     * @return the extracted fields
     */
    public DockerNetworkInspect networkInspect(JsonObject json) {
        Map<String, String> labels = extractStringMap(json, null, "Labels");
        return new DockerNetworkInspect(labels);
    }

    /**
     * Extracts string IDs from a JSON array of objects.
     *
     * @param array the JSON array
     * @return the list of ID strings
     */
    public List<String> idsFromArray(JsonArray array) {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("Id")) {
                ids.add(obj.get("Id").getAsString());
            }
        }
        return ids;
    }

    /**
     * Requires a string field from a JSON object, throwing if missing.
     *
     * @param json the JSON object
     * @param field the field name
     * @param description a human-readable description for error messages
     * @return the string value
     * @throws DockerRequestException if the field is missing
     */
    public String requireString(JsonObject json, String field, String description) {
        if (!json.has(field)) {
            throw new DockerRequestException(
                    "Missing required field '" + field + "' (" + description + ") in response");
        }
        return json.get(field).getAsString();
    }

    private static boolean jsonBoolean(JsonObject obj, String field, boolean defaultVal) {
        if (obj.has(field)) {
            JsonElement element = obj.get(field);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
        }
        return defaultVal;
    }

    private static String jsonString(JsonObject obj, String field, String defaultVal) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return defaultVal;
    }

    /**
     * Extracts a string-to-string map from a nested JSON object field.
     *
     * <p>The returned map is always unmodifiable. Returns {@link Map#of()}
     * (empty unmodifiable map) when the parent or field is absent or null.
     *
     * @param json the top-level JSON object
     * @param parent the parent key, or {@code null} to read from the top level
     * @param field the field name containing the string map
     * @return an unmodifiable map (never {@code null})
     */
    private static Map<String, String> extractStringMap(JsonObject json, String parent, String field) {
        JsonObject source = parent != null ? json.getAsJsonObject(parent) : json;
        if (source == null || !source.has(field) || source.get(field).isJsonNull()) {
            return Map.of();
        }
        JsonObject labelsObj = source.getAsJsonObject(field);
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : labelsObj.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<DockerPortBinding>> extractPorts(JsonObject json) {
        JsonObject networkSettings = json.getAsJsonObject("NetworkSettings");
        if (networkSettings == null
                || !networkSettings.has("Ports")
                || networkSettings.get("Ports").isJsonNull()) {
            return Map.of();
        }
        JsonObject portsObj = networkSettings.getAsJsonObject("Ports");
        Map<String, List<DockerPortBinding>> result = new LinkedHashMap<>();
        for (var entry : portsObj.entrySet()) {
            List<DockerPortBinding> bindings = new ArrayList<>();
            JsonArray arr = entry.getValue().getAsJsonArray();
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject binding = el.getAsJsonObject();
                    String hostIp =
                            binding.has("HostIp") && !binding.get("HostIp").isJsonNull()
                                    ? binding.get("HostIp").getAsString()
                                    : null;
                    String hostPort =
                            binding.has("HostPort") && !binding.get("HostPort").isJsonNull()
                                    ? binding.get("HostPort").getAsString()
                                    : null;
                    bindings.add(new DockerPortBinding(hostIp, hostPort));
                }
            }
            result.put(entry.getKey(), List.copyOf(bindings));
        }
        return Map.copyOf(result);
    }
}
