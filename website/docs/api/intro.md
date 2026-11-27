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
| `ContainerSpec` / `GenericContainerSpec` / `GenericContainerSpec.Builder` | Immutable container configuration (interface, implementation, and fluent builder) |
| `Network` | Interface for creating and managing Docker bridge networks |
| `WaitStrategy` | Functional interface for container readiness strategies |
| `Wait` | Convenience factory and composition methods for wait strategies |
| `HttpWaitStrategy` | HTTP/HTTPS readiness strategy with configurable protocol and status range |
| `PortWaitStrategy` | TCP port probe readiness strategy |
| `LogWaitStrategy` | Log message matching readiness strategy with regex and count |
| `Ulimit` | Linux resource limit specification |
| `BindMount` | Host-to-container bind mount specification |
| `ContainerException` | Runtime exception for lifecycle and configuration failures |
| `Version` | Library version information |
| `HttpWaitStrategy.Protocol` | HTTP protocol variant enum nested in HttpWaitStrategy |
| `OutputFrame` | Raw container output frame with stream metadata |
| `StartupCheckStrategy` | Functional interface for startup-check strategies evaluated after Docker starts the container |

Composite strategies are represented by `AllOfWaitStrategy` and `AnyOfWaitStrategy`, with
`CompositeWaitStrategy` as their public abstract base class. Their constructors are
package-private; create them with `Wait.allOf(...)` and `Wait.anyOf(...)`.

## Design patterns

- **Static factory methods.** `Container.create(spec)` and `Network.create()` are the entry points for creating resources. `Container.close(container)` and `Network.close(network)` for destruction.
- **Builder pattern.** `ContainerSpec.builder(image)` returns a mutable builder; `.build()` produces an immutable spec.
- **Try-with-resources.** `Container` and `Network` implement `AutoCloseable` for deterministic cleanup.
- **Internal implementation.** The core implementation (Docker client factory, container manager, network manager, and reaper client) lives under `nonapi.org.altcontainers.api` and is not part of the public API. The standalone reaper process lives under `org.altcontainers.reaper`.

## Package structure

```
org.altcontainers.api            — public API (Container, ContainerSpec, Network, ...)
nonapi.org.altcontainers.api     — internal core implementation
org.altcontainers.reaper         — standalone reaper process (automatic cleanup)
```

The `nonapi` prefix signals that those packages are internal and subject to change without
notice.

## Learn next

- [ContainerSpec](container-spec)
- [Container](container)
- [Network](network)
- [Javadoc](javadocs)
