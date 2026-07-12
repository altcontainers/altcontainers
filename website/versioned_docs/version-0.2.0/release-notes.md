---
title: Release Notes
description: Altcontainers release history and changelog.
---

# Release Notes

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
