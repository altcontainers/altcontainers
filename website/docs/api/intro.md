---
title: API Overview
description: Overview of the Altcontainers public API.
---

# API Overview

The Altcontainers public API lives in the `org.altcontainers.api` package.

## Core classes

| Class | Role |
|---|---|
| `Container` | Static factory for creating and destroying containers |
| `ContainerSpec` / `ContainerSpec.Builder` | Immutable container configuration with fluent builder |
| `Network` | Static factory for creating and destroying Docker bridge networks |
| `WaitCondition` | Sealed class hierarchy for container readiness conditions |
| `PrefixConsumer` | Formatted stdout log consumer |
| `Ulimit` | Linux resource limit specification |
| `BindMount` | Host-to-container bind mount specification |
| `ContainerException` | Runtime exception for lifecycle failures |
| `Version` | Library version information |
| `Protocol` | HTTP protocol variant for readiness probes |

## Design patterns

- **Static factory methods.** `Container.create(spec)` and `Network.create()` are the entry points for creating resources. `Container.destroy(container)` and `Network.destroy(network)` for destruction.
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
