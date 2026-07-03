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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ImageReference")
class ImageReferenceTest {

    @Test
    @DisplayName("rejects null image")
    void rejectsNullImage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ImageReference.parse(null))
                .withMessageContaining("image must not be blank");
    }

    @Test
    @DisplayName("rejects blank image")
    void rejectsBlankImage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ImageReference.parse("  "))
                .withMessageContaining("image must not be blank");
    }

    @Test
    @DisplayName("parses registry with port and no tag as latest")
    void parsesRegistryPortWithoutTag() {
        ImageReference ref = ImageReference.parse("localhost:5000/repo");

        assertThat(ref.fromImage()).isEqualTo("localhost:5000/repo");
        assertThat(ref.tag()).isEqualTo("latest");
    }

    @Test
    @DisplayName("treats colon-before-slash as ambiguous, defaults to latest")
    void parsesColonBeforeSlashAsDefaultTag() {
        ImageReference ref = ImageReference.parse("some:hub/path");

        assertThat(ref.fromImage()).isEqualTo("some:hub/path");
        assertThat(ref.tag()).isEqualTo("latest");
    }

    @Test
    @DisplayName("equals and hashCode for references with tags")
    void equalsAndHashCodeWithTags() {
        ImageReference a = ImageReference.parse("alpine:3.20");
        ImageReference b = ImageReference.parse("alpine:3.20");
        ImageReference c = ImageReference.parse("alpine:3.21");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("equals and hashCode for digest references with null tag")
    void equalsAndHashCodeWithNullTag() {
        ImageReference a = ImageReference.parse("repo/image@sha256:abc");
        ImageReference b = ImageReference.parse("repo/image@sha256:abc");
        ImageReference c = ImageReference.parse("repo/image@sha256:def");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("toString for tagged reference")
    void toStringWithTag() {
        ImageReference ref = ImageReference.parse("alpine:3.20");

        assertThat(ref.toString()).contains("alpine").contains("3.20");
    }

    @Test
    @DisplayName("toString for digest reference")
    void toStringWithNullTag() {
        ImageReference ref = ImageReference.parse("repo/image@sha256:abc");

        assertThat(ref.toString()).contains("repo/image@sha256:abc").contains("null");
    }

    @Test
    @DisplayName("image with trailing colon returns null tag")
    void parse_imageWithTrailingColon_returnsNullTag() {
        ImageReference ref = ImageReference.parse("myimage:");

        assertThat(ref.fromImage()).isEqualTo("myimage");
        assertThat(ref.tag()).isNull();
    }

    @Test
    @DisplayName("image with port and trailing colon returns null tag")
    void parse_imageWithPortAndTrailingColon_returnsNullTag() {
        ImageReference ref = ImageReference.parse("localhost:5000/repo:");

        assertThat(ref.fromImage()).isEqualTo("localhost:5000/repo");
        assertThat(ref.tag()).isNull();
    }
}
