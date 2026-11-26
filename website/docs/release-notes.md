---
title: Release Notes
description: Altcontainers release history and changelog.
---

# Release Notes

## 0.5.0

### New Features

- **Read-only bind mounts** — `bindDirectory(hostPath, containerPath, true)` and
  `new BindMount(hostPath, containerPath, true)` mount a host directory read-only;
  `BindMount.readOnly()` exposes the mode.

### Improvements

- **Verified reaper cleanup** — session teardown runs up to four sweeps within a five-minute drain
  deadline, re-discovers leftover resources, retries resource listings, and exits with status `2`
  when labeled resources remain instead of silently abandoning them.
- **Bounded container close** — a graceful stop is followed by a synchronous force-remove bounded
  at 10 seconds; the reaper is used only when that also fails, or during shutdown/interrupt.
- **Image pull robustness** — untagged image references pull `latest`, concurrent pulls of the same
  image are deduplicated, an in-flight pull survives a caller timeout, and a cached image that was
  removed from the daemon is pulled again before creation is retried.
- **Correct HTTPS trust-all probes** — `HTTPS_INSECURE` now disables hostname verification as
  documented.
- **Version resource isolation** — `Version` loads `version.properties` from its own package rather
  than an arbitrary root-level resource.
- **Earlier spec validation** — `Container.create(spec)` validates `startupTimeout` and
  `startupAttempts` before any Docker work; an `Error` from a `prepare` hook destroys the attempt's
  container and propagates without retry.
- **Reaper property forwarding** — `altcontainers.reaper.*` system properties (including
  `altcontainers.reaper.cleanup.max.attempts` and log settings) are forwarded to the reaper process.
- **Shutdown handling** — container and network stop executors are drained during JVM shutdown,
  and in-flight removals delegate to the reaper instead of racing it.

### Bug Fixes

- Various lifecycle, cleanup, and edge-case hardening, including null network settings and
  stale cached images.

### Notes

- Dependency updates across Maven plugins and runtime libraries.

## 0.4.2

### Bug Fixes

- Pinned the Maven Wrapper to Maven 3.9.x for Central publishing compatibility.

### Notes

- Dependency updates across Maven plugins and runtime libraries.

## 0.4.1

### Improvements

- Miscellaneous lifecycle, wait-strategy, and reaper hardening.
- Updated documentation and added versioned documentation for 0.4.1.

### Notes

- Dependency updates across Maven plugins and runtime libraries.

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

- Dependency updates across Maven plugins and runtime libraries.

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
- **Shaded uber-JAR** — relocated dependencies to isolate internal copies from application dependencies
- **Java 17+** — records and modern Java features
