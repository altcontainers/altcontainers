---
title: Ulimit
description: Immutable specification of a Linux resource limit for Docker containers.
---

# Ulimit

`Ulimit` is an immutable record representing a Linux resource limit.

```java
package org.altcontainers.api;

public record Ulimit(String name, long soft, long hard) {
    public Ulimit {
        // validates: name not blank, soft >= 0, hard >= 0,
        //           soft <= hard when hard > 0
    }
}
```

## Usage

Configured through `ContainerSpec.Builder`:

```java
import org.altcontainers.api.Ulimit;

ContainerSpec containerSpec = ContainerSpec.builder("my-image")
    .ulimit("nofile", 65536, 65536)   // max open files
    .ulimit("nproc", 4096, 4096)      // max processes
    .build();
```

Or create instances directly:

```java
Ulimit nofile = new Ulimit("nofile", 65536, 65536);
```

## Common ulimit names

| Name | Description |
|---|---|
| `nofile` | Maximum number of open file descriptors |
| `nproc` | Maximum number of processes |
| `memlock` | Maximum locked-in-memory address space |

Ulimits are OCI-standard and apply identically across Docker, Podman, and other OCI-compatible runtimes.

## Validation

The compact constructor enforces:
- `name` must not be null or blank
- `soft >= 0`
- `hard >= 0`
- `soft <= hard` when `hard > 0`

## Learn next

- [ContainerSpec](container-spec)
- [Core Concepts: Resource Limits](../core-concepts/resource-limits)
