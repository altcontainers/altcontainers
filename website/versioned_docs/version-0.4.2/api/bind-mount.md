---
title: BindMount
description: Host-to-container bind mount specification for Altcontainers container configuration.
---

# BindMount

`BindMount` is an immutable record specifying a host-to-container read-write bind mount.

```java
package org.altcontainers.api;

public record BindMount(String hostPath, String containerPath) {
}
```

## Usage

Bind mounts are configured through `ContainerSpec.Builder.bindDirectory()`:

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-image:latest")
    .bindDirectory("/host/path", "/container/path")
    .build();
```

Access all configured bind mounts from a `ContainerSpec`:

```java
List<BindMount> mounts = containerSpec.bindMounts();
for (BindMount mount : mounts) {
    System.out.println(mount.hostPath() + " -> " + mount.containerPath());
}
```

## Fields

### `hostPath`

The absolute host directory path to mount into the container. Must not be `null` or blank.

### `containerPath`

The absolute in-container mount target path. Must not be `null` or blank.

## Validation

The compact constructor enforces:
- `hostPath` must not be `null` or blank
- `containerPath` must not be `null` or blank

## Learn next

- [ContainerSpec](container-spec)
- [API Overview](intro)
