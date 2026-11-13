---
title: Release Notes
description: Altcontainers release history and changelog.
---

# Release Notes

## 0.4.0

### Breaking Changes

- **Removed the programmatic configuration API** — `Altcontainers.configure(...)`, `Altcontainers.configuration()`, and the `AltcontainersConfiguration` class were removed. Configuration is now exclusively file/environment/system-property based (`altcontainers.properties`, `~/.altcontainers.properties`, `ALTCONTAINERS_*` env vars, `-D` flags).
- **Removed `StartupContext` and `StartupFailure`** — these immutable callback-context records were part of the removed configuration API surface.

### New Features

- **Configurable image pull timeout** — new `altcontainers.image.pull.timeout.ms` property (default `300000`, 5 minutes) bounds how long image pulls may take before startup fails.

### Bug Fixes

- **Uber-JAR packaging fixed** — the shaded JAR now ships complete `META-INF/LICENSE` and `META-INF/NOTICE` files; third-party notices consolidated into `NOTICE`.
- **Launcher starts the reaper** — `Launcher.launch()` no longer skips reaper process startup in uber-JAR mode.
- **LogWaitStrategy frame-vs-line semantics fixed** — log matching operates on complete lines rather than raw transport frames.
- **Stale container status cache fixed** — `Container.isRunning()` now performs a live daemon query per call and reports `false` on daemon errors (including a vanished container).
- **PutArchive tar writer errors surfaced** — archive write errors are no longer silently swallowed; archive streaming uses a configurable pipe buffer.
- **Failure log callback no longer double-fires** on startup failure.
- **Disabled reaper no longer spams logs** during ensure-ready.
- **Network config precedence leak fixed** in `NetworkManager`.
- **Null network settings handled** after post-start inspection — `hostPort()` and friends no longer fail when Docker returns missing network data.

### Notes

- Dependency updates across Maven plugins and runtime libraries (Spotless 3.9.0, docker-java 3.7.1 / 6.1.3 line, logback 1.6.1, and more).

## 0.3.0

### Improvements

- **Safe output frame decoding** — `OutputFrame.safeUtf8String()` and `safeUtf8StringWithoutLineEnding()` strip NUL bytes, unsafe C0/C1 control characters, DEL, and ANSI CSI/OSC escape sequences so captured output is safe for terminal display and text log files.
- **Hardened reaper** — improved connection handshake robustness and lifecycle handling for the reaper watchdog process.
- **Documentation versioning** — the documentation site now maintains versioned docs (0.1.0–0.3.0) with an "Unreleased" version for the next release.

## 0.2.0

### Improvements

- **Eager per-resource cleanup** — The reaper now processes per-container and per-network termination commands immediately during the session, rather than relying solely on disconnect-time batch cleanup.
- **Idempotent cleanup handling** — Already-stopped or already-removed Docker resources are tolerated without error. Normal cleanup races no longer produce spurious failures.
- **Shutdown robustness** — The default reaper stop timeout is now 30 seconds (increased from 5 seconds), giving the reaper more time to complete cleanup before forced termination.
- **Shared HTTP probe clients** — HTTP wait strategies reuse a single `HttpClient` instance per protocol (HTTP, HTTPS insecure, HTTPS verify) instead of building a new client for each strategy instance.
- **Immutable lifecycle callback lists** — Copied container specifications now retain immutable copies of lifecycle-consumer lists via `List.copyOf()` rather than mutable `ArrayList` copies.

## 0.1.0

Initial release of Altcontainers as a standalone library.

### Features

- **Container lifecycle management** — create, start, wait for readiness, and destroy Docker containers
- **Readiness waiting** — port probes, HTTP response checks, and log message matching
- **Network management** — create and destroy Docker bridge networks with DNS aliases
- **Resource limits** — CPU shares, memory limits, shm size, and ulimits
- **Retry with backoff** — configurable startup attempts with linear backoff
- **Automatic cleanup** — reaper-based resource reclamation on JVM exit
- **Shaded uber-JAR** — relocated dependencies to prevent classpath conflicts
- **Java 17+** — records, sealed classes, and modern Java features
