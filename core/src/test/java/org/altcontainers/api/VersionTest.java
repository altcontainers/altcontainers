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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Version}.
 */
class VersionTest {

    @Test
    void versionShouldReturnNonNullString() {
        assertThat(Version.version()).isNotNull();
    }

    @Test
    void unknownConstantShouldBeUnknown() {
        assertThat(Version.UNKNOWN).isEqualTo("UNKNOWN");
    }

    @Test
    void loadVersionShouldReturnVersionFromProperties() {
        String props = "version=1.2.3\n";
        InputStream stream = new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8));
        String result = Version.loadVersion(name -> stream);
        assertThat(result).isEqualTo("1.2.3");
    }

    @Test
    void loadVersionShouldRejectNullProvider() {
        assertThatThrownBy(() -> Version.loadVersion(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resourceProvider");
    }

    @Test
    void loadVersionShouldReturnUnknownWhenProviderReturnsNull() {
        String result = Version.loadVersion(name -> null);
        assertThat(result).isEqualTo(Version.UNKNOWN);
    }

    @Test
    void loadVersionShouldReturnUnknownOnIoException() {
        String result = Version.loadVersion(name -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read error");
            }
        });
        assertThat(result).isEqualTo(Version.UNKNOWN);
    }

    @Test
    void loadVersionShouldReturnUnknownWhenPropertyMissing() {
        String props = "other=value\n";
        InputStream stream = new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8));
        String result = Version.loadVersion(name -> stream);
        assertThat(result).isEqualTo(Version.UNKNOWN);
    }

    @Test
    void loadVersionShouldReturnUnknownWhenPropertyBlank() {
        String props = "version=   \n";
        InputStream stream = new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8));
        String result = Version.loadVersion(name -> stream);
        assertThat(result).isEqualTo(Version.UNKNOWN);
    }

    @Test
    void loadVersionShouldReturnUnknownWhenPropertyEmpty() {
        String props = "version=\n";
        InputStream stream = new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8));
        String result = Version.loadVersion(name -> stream);
        assertThat(result).isEqualTo(Version.UNKNOWN);
    }

    @Test
    void loadVersionShouldAcceptValidSemver() {
        String props = "version=0.1.0-SNAPSHOT\n";
        InputStream stream = new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8));
        String result = Version.loadVersion(name -> stream);
        assertThat(result).isEqualTo("0.1.0-SNAPSHOT");
    }

    @Test
    void versionShouldBeLoadedFromBundledResource() {
        assertThat(Version.version()).isNotEqualTo(Version.UNKNOWN);
    }

    @Test
    void openResourceShouldIgnoreForeignRootVersionResource(@TempDir Path dir) throws IOException {
        // An application commonly ships its own root-level version.properties; it must
        // not be reported as the Altcontainers version.
        Files.writeString(dir.resolve("version.properties"), "version=0.0.0-foreign\n", StandardCharsets.UTF_8);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader foreign = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            thread.setContextClassLoader(foreign);
            assertThat(Version.loadVersion(Version::openResource))
                    .isNotEqualTo("0.0.0-foreign")
                    .isEqualTo(Version.version());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
