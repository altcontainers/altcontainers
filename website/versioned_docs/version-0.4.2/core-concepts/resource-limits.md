---
title: Resource Limits
description: Configuring CPU, memory, and ulimit constraints for Altcontainers containers.
---

# Resource Limits

Altcontainers supports Docker resource constraints through the `ContainerSpec.Builder`.

## Memory limits

```java
ContainerSpec.builder("my-image")
    .memory(512 * 1024 * 1024)       // 512 MB
    .memorySwap(1024 * 1024 * 1024)  // 1 GB total (memory + swap)
    .build();
```

- `.memory(bytes)` — container memory limit; 0 = no explicit limit
- `.memorySwap(bytes)` — total memory + swap limit; 0 = unlimited

## CPU limits

```java
ContainerSpec.builder("my-image")
    .cpuShares(512)        // relative weight (default 1024)
    .cpuPeriod(100000)     // CFS period in microseconds
    .cpuQuota(50000)       // CFS quota in microseconds (50% of period)
    .build();
```

- `.cpuShares(int)` — CPU share weight; 0 = no explicit limit
- `.cpuPeriod(long)` — CFS period in microseconds; 0 = no explicit limit
- `.cpuQuota(long)` — CFS quota in microseconds; 0 = no explicit limit

## Shared memory (`/dev/shm`)

```java
ContainerSpec.builder("my-image")
    .shmSize(256 * 1024 * 1024)  // 256 MB
    .build();
```

## Ulimits

Linux resource limits for processes inside the container:

```java
import org.altcontainers.api.Ulimit;

ContainerSpec.builder("my-image")
    .ulimit("nofile", 65536, 65536)  // max open files
    .ulimit("nproc", 4096, 4096)     // max processes
    .build();
```

Common ulimit names: `"nofile"`, `"nproc"`, `"memlock"`.

Ulimits are OCI-standard and apply identically across Docker, Podman, and other OCI-compatible runtimes. Duplicate ulimit names are allowed (last one wins at the Docker level).

## Learn next

- [Cleanup](cleanup)
- [API: ContainerSpec.Builder](../api/container-spec)
- [API: Ulimit](../api/ulimit)
