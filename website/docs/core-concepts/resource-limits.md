---
title: Resource Limits
description: Configuring CPU, memory, and ulimit constraints for Altcontainers containers.
---

# Resource Limits

Altcontainers supports Docker resource constraints through the `ContainerSpec` builder
(`GenericContainerSpec.Builder`).

## Memory limits

```java
ContainerSpec.builder("my-image")
    .memory(512 * 1024 * 1024)       // 512 MiB
    .memorySwap(1024 * 1024 * 1024)  // 1 GiB total (memory + swap)
    .build();
```

- `.memory(bytes)` — container memory limit; 0 = no explicit limit
- `.memorySwap(bytes)` — total memory + swap limit; 0 = unlimited

## CPU limits

```java
ContainerSpec.builder("my-image")
    .cpuShares(512)        // relative weight; Docker default is 1024 when unset
    .cpuPeriod(100000)     // CFS period in microseconds
    .cpuQuota(50000)       // CFS quota in microseconds (50% of period)
    .build();
```

- `.cpuShares(int)` — CPU share weight; 0 = no explicit limit (Docker default applies)
- `.cpuPeriod(long)` — CFS period in microseconds; 0 = no explicit limit
- `.cpuQuota(long)` — CFS quota in microseconds; 0 = no explicit limit

## Shared memory (`/dev/shm`)

```java
ContainerSpec.builder("my-image")
    .shmSize(256 * 1024 * 1024)  // 256 MiB; 0 = Docker default
    .build();
```

## Ulimits

Linux resource limits for processes inside the container:

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-image")
    .ulimit("nofile", 65536, 65536)  // max open files
    .ulimit("nproc", 4096, 4096)     // max processes
    .build();

// Remove all configured ulimits from a derived spec
ContainerSpec withoutUlimits = containerSpec.toBuilder().clearUlimits().build();
```

Common ulimit names: `"nofile"`, `"nproc"`, `"memlock"`. Ulimits are passed to Docker
through `HostConfig.withUlimits()`; the builder allows duplicate names.

## Learn next

- [Cleanup](cleanup)
- [API: ContainerSpec](../api/container-spec)
- [API: Ulimit](../api/ulimit)
