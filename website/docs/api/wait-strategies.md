---
title: Wait Strategies
description: Readiness strategies for container startup — port probes, HTTP checks, log matching, and composition.
---

# Wait Strategies

Wait strategies tell Altcontainers when a container is ready. They implement the `WaitStrategy` interface and can be used through builder convenience methods, direct construction, or factory methods.

## Builder convenience methods

The simplest path: use `ContainerSpec.Builder` methods.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForContainerPort(8080)
    .build();
```

## Direct construction

All built-in strategies are public classes with public constructors:

```java
WaitStrategy port = PortWaitStrategy.builder().port(8080).build();
WaitStrategy log = LogWaitStrategy.builder().pattern(".*started.*").build();
WaitStrategy http = HttpWaitStrategy.builder().port(8080).path("/health").build();
```

## Factory convenience

The `Wait` class provides discoverable static factory methods equivalent to direct construction:

```java
WaitStrategy port = Wait.forListeningPort(8080);
WaitStrategy log = Wait.forLogMessage(".*started.*", 1);
WaitStrategy http = Wait.forHttpResponse(8080, "/health", Protocol.HTTP, 200, 399);
```

## Composition

Combine strategies with `Wait.allOf()` and `Wait.anyOf()`:

```java
WaitStrategy ready = Wait.allOf(
    PortWaitStrategy.builder().port(8080).build(),
    LogWaitStrategy.builder().pattern(".*started.*").build()
);

ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForStrategy(ready)
    .build();
```

## Custom strategies

Implement `WaitStrategy` directly for custom readiness logic:

```java
WaitStrategy fileReady = container -> {
    int port = container.hostPort(8080);
    return port > 0;
};
```

Strategies that observe logs override `logLineConsumer()` to receive raw log lines.

## Built-in types

### PortWaitStrategy

Satisfied when the mapped host port accepts a TCP connection. Stateless.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForContainerPort(8080)
    .build();
```

### HttpWaitStrategy

Satisfied when an HTTP/HTTPS GET returns a status in the acceptable range. Stateless.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")                    // 200..399
    .waitForHttpResponse(8080, "/health", 200, 200)          // exactly 200
    .waitForHttpsResponse(8443, "/health")                   // HTTPS, 200..399
    .build();
```

### LogWaitStrategy

Satisfied when a log line matching a regex has appeared the required number of times. Stateful; counters reset between startup attempts.

```java
ContainerSpec.builder("my-image")
    .waitForLogMessage(".*started.*")       // one occurrence
    .waitForLogMessage(".*ready.*", 3)      // three occurrences
    .build();
```

## Evaluation

- Strategies are polled every ~250ms with random jitter
- Satisfied strategies are removed from the pending set
- All strategies must be satisfied before the `startupTimeout` elapses
- Stateful strategies (log matching) reset between startup attempts

## Learn next

- [ContainerSpec](container-spec)
- [Core Concepts: Wait Strategies](../core-concepts/wait-strategies)
