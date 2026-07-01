---
title: Release Notes
description: Altcontainers release history and changelog.
---

# Release Notes

## 0.0.1 (Unreleased)

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

### Migration from Paramixel Containers

Altcontainers was extracted from the Paramixel `containers/` module. If you previously used `org.paramixel:containers`:

1. Change the Maven dependency to `org.altcontainers:core:0.0.1`
2. Update imports from `org.paramixel.container.api` to `org.altcontainers.api`
3. The API is otherwise identical
