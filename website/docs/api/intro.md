---
title: API Overview
description: Overview of the Altcontainers public API.
---

# API Overview

The Altcontainers public API lives in the `org.altcontainers.api` package.

## Core classes

| Class | Role |
|---|---|
| `ContainerManager` | Singleton entry point for creating and destroying containers |
| `ContainerSpec` / `ContainerSpec.Builder` | Immutable container configuration with fluent builder |
| `Container` | Runtime handle to a started container |
| `NetworkManager` | Singleton entry point for creating and destroying networks |
| `Network` | Runtime handle to a Docker bridge network |
| `PrefixConsumer` | Formatted stdout log consumer |
| `Ulimit` | Linux resource limit specification |
| `ContainerException` | Runtime exception for lifecycle failures |
| `Version` | Library version information |

## Design patterns

- **Singleton managers.** `ContainerManager.getInstance()` and `NetworkManager.getInstance()` are the only entry points.
- **Builder pattern.** `ContainerSpec.builder(image)` returns a mutable builder; `.build()` produces an immutable spec.
- **Try-with-resources.** `Container` and `Network` implement `AutoCloseable` for deterministic cleanup.
- **Shaded internals.** All implementation classes (Docker client, reaper, wait conditions) live under `nonapi.org.altcontainers` and are not part of the public API.

## Package structure

```
org.altcontainers.api          — public API (Container, ContainerManager, ContainerSpec, ...)
nonapi.org.altcontainers       — internal implementation (DockerClient, WaitCondition, ...)
nonapi.org.altcontainers.reaper — automatic cleanup infrastructure
nonapi.org.altcontainers.logger — internal logging
```

The `nonapi` prefix signals that these packages are internal and subject to change without notice.

## Learn next

- [ContainerManager](container-manager)
- [ContainerSpec](container-spec)
- [Container](container)
- [Javadoc](javadocs)
