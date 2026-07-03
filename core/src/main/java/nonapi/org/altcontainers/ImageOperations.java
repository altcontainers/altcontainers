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

import nonapi.org.altcontainers.docker.DockerNotFoundException;
import org.altcontainers.api.ContainerException;

/**
 * Image-related Docker operations.
 *
 * <p>Package-private static utility. Takes {@link DockerClient} as the first argument to access the
 * configured Docker HTTP client.
 */
public final class ImageOperations {

    private ImageOperations() {
        // Intentionally empty
    }

    /**
     * Ensures the given Docker image is present on the Docker host, pulling it if it is missing.
     *
     * @param client the Docker client
     * @param image the Docker image name; must not be blank
     * @throws IllegalArgumentException if {@code image} is blank
     * @throws ContainerException if the image cannot be inspected or pulled
     */
    public static void pullImageIfMissing(DockerClient client, String image) {
        DockerClient.requireNonBlank(image, "image");
        try {
            client.delegate().inspectImage(image);
        } catch (DockerNotFoundException e) {
            try {
                client.delegate().pullImage(image);
            } catch (RuntimeException pullError) {
                throw new ContainerException("Failed to pull image: " + image, pullError);
            }
        } catch (RuntimeException e) {
            throw new ContainerException("Failed to inspect image: " + image, e);
        }
    }
}
