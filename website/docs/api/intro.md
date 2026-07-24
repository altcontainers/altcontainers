---
title: API Overview
description: Overview of the Altcontainers public API.
---

# API Overview

The Altcontainers public API lives in the `org.altcontainers.api` package.

## Core classes

| Class | Role |
|---|---|
| `Container` | Interface for creating and managing Docker containers |
| `ContainerSpec` / `GenericContainerSpec.Builder` | Immutable container configuration with fluent builder |
| `Network` | Interface for creating and managing Docker bridge networks |
| `WaitStrategy` | Functional interface for container readiness strategies |
| `Wait` | Convenience factory and composition methods for wait strategies |
| `HttpWaitStrategy` | HTTP/HTTPS readiness strategy with configurable protocol and status range |
| `PortWaitStrategy` | TCP port probe readiness strategy |
| `LogWaitStrategy` | Log message matching readiness strategy with regex and count |
| `Ulimit` | Linux resource limit specification |
| `BindMount` | Host-to-container bind mount specification |
| `ContainerException` | Runtime exception for lifecycle failures |
| `Version` | Library version information |
| `HttpWaitStrategy.Protocol` | HTTP protocol variant enum nested in HttpWaitStrategy |
| `OutputFrame` | Raw container output frame with stream metadata |
| `StartupContext` | Immutable context passed to startup-phase lifecycle callbacks |
| `StartupFailure` | Immutable context passed to failed-startup-attempt lifecycle callbacks |
| `StartupCheckStrategy` | Functional interface for startup-check strategies |

## Design patterns

- **Static factory methods.** `Container.create(spec)` and `Network.create()` are the entry points for creating resources. `Container.close(container)` and `Network.close(network)` for destruction.
- **Builder pattern.** `ContainerSpec.builder(image)` returns a mutable builder; `.build()` produces an immutable spec.
- **Try-with-resources.** `Container` and `Network` implement `AutoCloseable` for deterministic cleanup.
- **Shaded internals.** All implementation classes (Docker client, reaper, container manager, network manager) live under `nonapi.org.altcontainers` and are not part of the public API.

## Package structure

```
org.altcontainers.api          — public API (Container, ContainerSpec, Network, ...)
nonapi.org.altcontainers       — internal implementation (DockerClient, ContainerManager, NetworkManager, ...)
nonapi.org.altcontainers.reaper — automatic cleanup infrastructure
```

The `nonapi` prefix signals that these packages are internal and subject to change without notice.

## Learn next

- [ContainerSpec](container-spec)
- [Container](container)
- [Network](network)
- [Javadoc](javadocs)
