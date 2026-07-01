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

package org.altcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the {@code containers} shaded uber jar. Asserts the build invariants that
 * prevent class-loading collisions and guarantee a correct NOTICE/LICENSE, so future dependency
 * changes cannot silently regress them.
 *
 * <p>Runs in the {@code integration-test} phase (failsafe), after {@code package}, so the uber jar
 * exists on disk.
 */
@Tag("integration")
class ContainersUberJarTest {

    private static final List<String> UNRELOCATED_THIRD_PARTY_ROOTS = List.of(
            "com/fasterxml/",
            "com/google/",
            "com/github/dockerjava/",
            "com/sun/jna/",
            "org/apache/",
            "org/bouncycastle/",
            "org/jspecify/",
            "org/slf4j/",
            "ch/qos/logback/");

    @Test
    void resolvesExactlyOneUberJar() throws IOException {
        assertThat(uberJar()).isRegularFile();
    }

    @Test
    void containsExactlyOneLicense() throws IOException {
        assertThat(entries("META-INF/LICENSE")).hasSize(1);
    }

    @Test
    void licenseIsApacheLicense() throws IOException {
        assertThat(firstLine("META-INF/LICENSE")).contains("Apache License");
    }

    @Test
    void containsExactlyOneProjectNotice() throws IOException {
        assertThat(entries("META-INF/NOTICE")).hasSize(1);
    }

    @Test
    void noticeIsAuthoredByProject() throws IOException {
        assertThat(firstLine("META-INF/NOTICE")).startsWith("Altcontainers");
    }

    @Test
    void containsNoUnrelocatedThirdPartyPackages() throws IOException {
        List<String> offending = new ArrayList<>();
        try (ZipFile jar = newZipFile()) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("nonapi/")
                        || name.startsWith("org/altcontainers/")
                        || name.startsWith("META-INF/")) {
                    continue;
                }
                for (String root : UNRELOCATED_THIRD_PARTY_ROOTS) {
                    if (name.startsWith(root)) {
                        offending.add(name);
                        break;
                    }
                }
            }
        }
        assertThat(offending)
                .as("uber jar must not contain un-relocated third-party packages")
                .isEmpty();
    }

    @Test
    void doesNotContainUnrelocatedSlf4j() throws IOException {
        assertThat(slf4jEntries())
                .as("slf4j must be relocated under nonapi/, not present at org/slf4j/")
                .isEmpty();
    }

    @Test
    void relocatesClassesUnderNonapiPrefix() throws IOException {
        assertThat(entriesUnder("nonapi/org/altcontainers/")).isNotEmpty();
    }

    private List<String> entries(String prefix) throws IOException {
        List<String> matches = new ArrayList<>();
        try (ZipFile jar = newZipFile()) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.equals(prefix) || name.startsWith(prefix + "/")) {
                    matches.add(name);
                }
            }
        }
        return matches;
    }

    private List<String> entriesUnder(String prefix) throws IOException {
        List<String> matches = new ArrayList<>();
        try (ZipFile jar = newZipFile()) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(prefix)) {
                    matches.add(name);
                }
            }
        }
        return matches;
    }

    private List<String> slf4jEntries() throws IOException {
        List<String> matches = new ArrayList<>();
        try (ZipFile jar = newZipFile()) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("org/slf4j/")) {
                    matches.add(name);
                }
            }
        }
        return matches;
    }

    private String firstLine(String entry) throws IOException {
        try (ZipFile jar = newZipFile()) {
            try (var in = jar.getInputStream(jar.getEntry(entry))) {
                String line = new String(in.readAllBytes())
                        .lines()
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse("");
                return line.trim();
            }
        }
    }

    private ZipFile newZipFile() throws IOException {
        return new ZipFile(uberJar().toFile());
    }

    private static Path projectBasedir() {
        return uberJarTarget().toAbsolutePath().getParent();
    }

    private static Path uberJar() throws IOException {
        Path target = uberJarTarget();
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(target)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("core-")
                                && !name.startsWith("original-")
                                && !name.contains("-javadoc")
                                && !name.contains("-sources");
                    })
                    .forEach(candidates::add);
        }
        assertThat(candidates)
                .as("expected exactly one core uber jar in target/")
                .hasSize(1);
        return candidates.get(0);
    }

    private static Path uberJarTarget() {
        Path target = Path.of("target");
        assertThat(target)
                .as("target/ must exist (run from module basedir after package)")
                .exists();
        return target;
    }
}
