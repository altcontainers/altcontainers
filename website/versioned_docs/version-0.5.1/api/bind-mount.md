---
title: BindMount
description: Host-to-container bind mount specification for Altcontainers container configuration.
---

# BindMount

`BindMount` is an immutable record specifying a host-to-container bind mount, either read-write or read-only.

```java
package org.altcontainers.api;

public record BindMount(String hostPath, String containerPath, boolean readOnly) {

    // Read-write bind mount (readOnly = false)
    public BindMount(String hostPath, String containerPath) { ... }
}
```

A bind mount shares the host directory with the container directly. Nothing is copied, so the cost of a
bind mount does not depend on how much data the directory holds, unlike `Container.copyFileToContainer()`.

## Usage

Bind mounts are configured through `ContainerSpec.Builder.bindDirectory()`:

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
    .bindDirectory("/host/path", "/container/path")             // read-write
    .bindDirectory("/host/config", "/container/config", true)   // read-only
    .build();
```

Prefer a read-only mount for inputs such as configuration, scripts, and test fixtures. The container
cannot modify the host directory, so the same host directory can be shared safely by many containers
without copying it first.

Access all configured bind mounts from a `ContainerSpec`:

```java
List<BindMount> mounts = containerSpec.bindMounts();
for (BindMount mount : mounts) {
    System.out.println(mount.hostPath() + " -> " + mount.containerPath() + (mount.readOnly() ? " (ro)" : ""));
}
```

## Fields

### `hostPath`

The absolute host directory path to mount into the container. Must not be `null` or blank.

### `containerPath`

The absolute in-container mount target path. Must not be `null` or blank.

### `readOnly`

`true` to mount read-only; `false` (the default for the two-argument constructor and
`bindDirectory(hostPath, containerPath)`) to mount read-write.

## Host path location

The host path is resolved by the Docker daemon, not by the JVM. With a remote daemon, or a daemon
running inside a VM (for example, Docker Desktop), the path must exist on that host or be shared with
it. When that is not possible, use `Container.copyFileToContainer()` instead.

## Validation

The compact constructor enforces:
- `hostPath` must not be `null` or blank
- `containerPath` must not be `null` or blank

## Learn next

- [ContainerSpec](container-spec)
- [API Overview](intro)
