---
title: ContainerSpec
description: Immutable desired configuration for a Docker container, built with a fluent builder.
---

# ContainerSpec

`ContainerSpec` captures the desired configuration for a Docker container. It is an interface with a static `builder(String)` factory that returns a `GenericContainerSpec.Builder`. Specs are consumed by `Container.create(spec)`.

## Builder example

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .command("nginx", "-g", "daemon off;")
    .exposePorts(80, 443)
    .network(network, "nginx")
    .waitForHttpResponse(80, "/")
    .startupTimeout(Duration.ofSeconds(30))
    .startupAttempts(2)
    .logConsumer(line -> System.out.println("[NGINX] nginx:1.27 | " + line))
    .memory(256 * 1024 * 1024)
    .build();
```

## Custom wait strategies

`waitForStrategy` accepts any `WaitStrategy` implementation: lambdas, directly constructed
built-in strategies, or composed strategies.

```java
// Direct construction
builder.waitForStrategy(HttpWaitStrategy.builder().port(8080).path("/health").build());

// Factory convenience
builder.waitForStrategy(Wait.forListeningPort(8080));

// Composition
builder.waitForStrategy(Wait.allOf(
    PortWaitStrategy.builder().port(8080).build(),
    LogWaitStrategy.builder().pattern(".*started.*").build()
));

// Custom lambda
builder.waitForStrategy(container -> container.hostPort(8080) > 0);
```

## Defaults

| Setting | Default |
|---|---|
| `startupTimeout` | 60 seconds |
| `startupAttempts` | 1 (no retry) |
| Memory limits | 0 (no explicit limit) |
| CPU limits | 0 (no explicit limit) |

## Immutability

`ContainerSpec` instances are immutable and safe to share between threads. The `GenericContainerSpec.Builder` is mutable and not thread-safe.

## Learn next

- [Container](container)
- [Container](container)
- [Javadoc](javadocs)
