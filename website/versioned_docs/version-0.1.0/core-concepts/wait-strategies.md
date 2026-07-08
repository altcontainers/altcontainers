---
title: Wait Strategies
description: Configuring readiness strategies for Altcontainers containers.
---

# Wait Strategies

Wait strategies tell Altcontainers when a container is ready to use. They are configured on `ContainerSpec.Builder` and evaluated during startup.

## Available strategies

### Port wait

Waits until the mapped host port accepts a TCP connection.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForContainerPort(8080)
    .build();
```

The port must also be exposed via `.exposePorts()`. This strategy is satisfied as soon as Docker's userland proxy binds the host port — it does not mean the service inside the container is ready to handle requests.

### HTTP response wait

Waits until an HTTP GET returns a status in an acceptable range. This proves the service is actually serving requests.

```java
ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80, "/")                     // 200..399
    .build();

// Or specify the exact status range:
ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health", 200, 200)  // exactly 200
    .build();

// HTTPS:
ContainerSpec.builder("my-service")
    .exposePorts(8443)
    .waitForHttpsResponse(8443, "/health")
    .build();
```

### Log message wait

Waits until a log line matching a regex has appeared the required number of times.

```java
ContainerSpec.builder("my-service")
    .waitForLogMessage(".*started.*")       // one occurrence
    .build();

ContainerSpec.builder("my-service")
    .waitForLogMessage(".*ready.*", 3)      // three occurrences
    .build();
```

## Composing wait strategies

Multiple strategies can be combined on the builder:

```java
ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")
    .waitForLogMessage(".*started.*")
    .build();
```

All strategies must be satisfied before the container is considered ready.

Strategies can also be composed with `Wait.allOf()` and `Wait.anyOf()` and passed
to `waitStrategy()`:

```java
ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitStrategy(Wait.allOf(
        PortWaitStrategy.builder().port(8080).build(),
        LogWaitStrategy.builder().pattern(".*started.*").build()
    ))
    .build();
```

## How wait strategies are evaluated

1. Strategies are checked in a polling loop with 250ms intervals (plus random jitter).
2. Satisfied strategies are removed from the pending set — they are never rechecked.
3. If the `startupTimeout` elapses before all strategies are satisfied, startup fails.
4. Failed startups are retried (if `startupAttempts > 1`) with fresh strategy state — log-match counters are reset for each attempt.

## Custom wait strategies

`WaitStrategy` is an open interface. Implement it directly for custom strategies, or compose built-in strategies with `Wait.allOf()` / `Wait.anyOf()`.

```java
// Lambda implementation
builder.waitStrategy(container -> {
    Integer port = container.hostPort(8080);
    return port != null && port > 0;
});

// Direct construction of built-in types
builder.waitStrategy(PortWaitStrategy.builder().port(8080).build());
builder.waitStrategy(HttpWaitStrategy.builder().port(8080).path("/health").build());

// Factory convenience
builder.waitStrategy(Wait.forListeningPort(8080));
builder.waitStrategy(Wait.forLogMessage(".*started.*", 1));
```

## Learn next

- [Container Lifecycle](container-lifecycle)
- [Networking](networking)
