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

package nonapi.org.altcontainers.reaper;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.altcontainers.api.ContainerException;

/**
 * Launches the global reaper daemon as a detached host process via {@code setsid} (Linux) or
 * {@code nohup} (macOS).
 *
 * <p>The daemon binds its own port and writes the discovery file. No port is allocated or
 * passed by the launcher.
 */
public final class Launcher {

    private Launcher() {
        // Intentionally empty
    }

    /**
     * Returns whether the host operating system is Linux.
     *
     * <p>Package-private for testability.
     *
     * @return {@code true} if {@code os.name} contains "linux" (case-insensitive)
     */
    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /**
     * Launches the global reaper daemon as a detached process.
     *
     * <p>Uses {@code setsid} on Linux and {@code nohup} on macOS and other platforms.
     *
     * @param config the reaper configuration
     * @throws ContainerException if the daemon cannot be started
     */
    public static void launch(Configuration configuration) {
        if (isLinux()) {
            launchWithSetsid(configuration);
        } else {
            launchWithNohup(configuration);
        }
    }

    /**
     * Launches the reaper daemon via {@code nohup} on non-Linux platforms.
     *
     * @param configuration the reaper configuration
     * @throws ContainerException if the daemon cannot be started
     */
    private static void launchWithNohup(Configuration configuration) {
        String javaExecutable = resolveJavaExecutable();
        String classpath = buildClasspath();

        String logDirectory = ReaperDiscovery.discoveryPath().getParent().toString();

        List<String> command = new ArrayList<>();
        command.add("nohup");
        command.add(javaExecutable);
        command.add("-cp");
        command.add(classpath);
        command.add("-Daltcontainers.reaper.daemon=true");
        command.add("-Daltcontainers.reaper.log.directory=" + logDirectory);
        command.add(
                "-Daltcontainers.reaper.cleanup.timeout.milliseconds=" + configuration.cleanupTimeoutMilliseconds());
        command.add("-Daltcontainers.reaper.idle.timeout.milliseconds=" + configuration.idleTimeoutMilliseconds());
        command.add("-Daltcontainers.reaper.log.level=" + configuration.logLevel());
        command.add(Reaper.class.getName());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // Best-effort.
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to launch reaper daemon", e);
        }
    }

    /**
     * Launches the reaper daemon via {@code setsid} on Linux.
     *
     * @param configuration the reaper configuration
     * @throws ContainerException if {@code setsid} is unavailable or the daemon cannot be started
     */
    private static void launchWithSetsid(Configuration configuration) {
        if (!isSetsidAvailable()) {
            throw new ContainerException("setsid is unavailable; cannot detach reaper daemon");
        }

        String javaExecutable = resolveJavaExecutable();
        String classpath = buildClasspath();

        String logDirectory = ReaperDiscovery.discoveryPath().getParent().toString();

        List<String> command = new ArrayList<>();
        command.add("setsid");
        command.add(javaExecutable);
        command.add("-cp");
        command.add(classpath);
        command.add("-Daltcontainers.reaper.daemon=true");
        command.add("-Daltcontainers.reaper.log.directory=" + logDirectory);
        command.add(
                "-Daltcontainers.reaper.cleanup.timeout.milliseconds=" + configuration.cleanupTimeoutMilliseconds());
        command.add("-Daltcontainers.reaper.idle.timeout.milliseconds=" + configuration.idleTimeoutMilliseconds());
        command.add("-Daltcontainers.reaper.log.level=" + configuration.logLevel());
        command.add(Reaper.class.getName());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // Best-effort.
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to launch reaper daemon", e);
        }
    }

    static boolean isSetsidAvailable() {
        try {
            Process process = new ProcessBuilder("setsid", "true")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String buildClasspath() {
        List<String> paths = new ArrayList<>();
        ClassLoader loader = Launcher.class.getClassLoader();
        while (loader != null) {
            if (loader instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    try {
                        paths.add(Paths.get(url.toURI()).toString());
                    } catch (Exception ignored) {
                        // Skip unparseable URLs.
                    }
                }
            }
            loader = loader.getParent();
        }
        String fallback = System.getProperty("java.class.path", "");
        if (!fallback.isBlank()) {
            for (String entry : fallback.split(File.pathSeparator)) {
                String trimmed = entry.trim();
                if (!trimmed.isBlank() && !paths.contains(trimmed)) {
                    paths.add(trimmed);
                }
            }
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String resolveJavaExecutable() {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            return command;
        }
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
