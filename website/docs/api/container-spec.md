---
title: ContainerSpec
description: Immutable desired configuration for a Docker container, built with a fluent builder.
---

# ContainerSpec

`ContainerSpec` captures the desired configuration for a Docker container. It is built via `ContainerSpec.builder(image)` and consumed by `Container.create(spec)`.

```java
package org.altcontainers.api;

public final class ContainerSpec {
    public static Builder builder(String image);

    public String image();

    public static final class Builder {
        public Builder command(String... parts);
        public Builder exposePorts(int... ports);
        public Builder network(Network network, String... aliases);
        public Builder workingDirectory(String directory);
        public Builder bindDirectory(String hostPath, String containerPath);
        public Builder logConsumer(Consumer<String> logger);
        public Builder startupTimeout(Duration duration);
        public Builder startupAttempts(int attempts);
        public Builder waitForContainerPort(int containerPort);
        public Builder waitForHttpResponse(int containerPort, String path);
        public Builder waitForHttpResponse(int containerPort, String path, int minStatus, int maxStatus);
        public Builder waitForHttpsResponse(int containerPort, String path);
        public Builder waitForHttpsResponse(int containerPort, String path, int minStatus, int maxStatus);
        public Builder waitForLogMessage(String regex);
        public Builder waitForLogMessage(String regex, int times);
        public Builder ulimit(String name, long soft, long hard);
        public Builder clearUlimits();
        public Builder memory(long bytes);
        public Builder memorySwap(long bytes);
        public Builder shmSize(long bytes);
        public Builder cpuShares(int shares);
        public Builder cpuPeriod(long period);
        public Builder cpuQuota(long quota);
        public ContainerSpec build();
    }
}
```

## Builder example

```java
ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .command("nginx", "-g", "daemon off;")
    .exposePorts(80, 443)
    .network(network, "nginx")
    .waitForHttpResponse(80, "/")
    .startupTimeout(Duration.ofSeconds(30))
    .startupAttempts(2)
    .logConsumer(PrefixConsumer.of("NGINX", "nginx:1.27"))
    .memory(256 * 1024 * 1024)
    .build();
```

## Defaults

| Setting | Default |
|---|---|
| `startupTimeout` | 60 seconds |
| `startupAttempts` | 1 (no retry) |
| Memory limits | 0 (no explicit limit) |
| CPU limits | 0 (no explicit limit) |

## Immutability

`ContainerSpec` instances are immutable and safe to share between threads. The `Builder` is mutable and not thread-safe.

## Learn next

- [Container](container)
- [Container](container)
- [Javadoc](javadocs)
