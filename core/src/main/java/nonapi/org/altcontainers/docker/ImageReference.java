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

/**
 * Parsed Docker image reference for constructing pull query parameters.
 *
 * @param fromImage the image repository (e.g., {@code "alpine"} or
 *     {@code "localhost:5000/repo/image"}), or the full digest reference for images specified by digest
 * @param tag the image tag, or {@code null} for digest references
 */
public record ImageReference(String fromImage, String tag) {

    /**
     * Parses a Docker image reference of the form {@code repository:tag}, {@code repository}, or
     * {@code repository@sha256:digest}.
     *
     * <p>When no tag is specified and no digest is present, the tag defaults to {@code "latest"}.
     * When a digest is present ({@code @sha256:...}), the tag is {@code null} and the full
     * reference (including the digest) is treated as {@code fromImage}.
     *
     * @param image the image reference string; must not be {@code null} or blank
     * @return the parsed reference
     */
    public static ImageReference parse(String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        // Check for digest reference: repo/image@sha256:abc...
        int atIndex = image.indexOf('@');
        if (atIndex >= 0) {
            return new ImageReference(image, null);
        }
        // Check for tag: repo:tag or localhost:5000/repo:tag
        int lastColon = image.lastIndexOf(':');
        if (lastColon > 0) {
            String remainder = image.substring(lastColon + 1);
            if (remainder.contains("/")) {
                // The colon was part of a port number, no tag
                return new ImageReference(image, "latest");
            }
            return new ImageReference(image.substring(0, lastColon), remainder.isEmpty() ? null : remainder);
        }
        return new ImageReference(image, "latest");
    }
}
