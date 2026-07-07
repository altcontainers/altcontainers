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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

/**
 * Singleton Docker client provider for core module.
 */
public final class DockerClientFactory {

    private DockerClientFactory() {
        // Intentionally empty
    }

    private static class ClientHolder {
        static final DockerClient INSTANCE = createClient();
    }

    /**
     * Returns the singleton Docker client.
     *
     * @return the singleton Docker client
     */
    public static DockerClient client() {
        return ClientHolder.INSTANCE;
    }

    /**
     * Returns the host clients should use for published container ports.
     *
     * @return the Docker host
     */
    public static String containerHost() {
        return DockerHostConfig.containerHost(DockerHostConfig.createDockerClientConfig());
    }

    private static DockerClient createClient() {
        var config = DockerHostConfig.createDockerClientConfig();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
