---
title: Wait Strategies
description: Configuring readiness strategies for Altcontainers containers.
---

# Wait Strategies

Wait strategies tell Altcontainers when a container is ready to use. They are configured on the
`ContainerSpec` builder (`GenericContainerSpec.Builder`) and evaluated during startup, after the
`StartupCheckStrategy` has succeeded. When multiple strategies are configured, all of them must be
satisfied.

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

Waits until an HTTP GET returns a status in an acceptable range. This proves the service is
actually serving requests. The port must also be exposed via `.exposePorts()`.

```java
ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80)              // HTTP GET /, 200..399
    .build();

// HTTPS (trust-all certificates):
ContainerSpec.builder("my-service")
    .exposePorts(8443)
    .waitForHttpsResponse(8443)
    .build();
```

For a custom path or exact status range, construct `HttpWaitStrategy` directly. The default
status range is `200..399`; `statusRange()` accepts values from `100` to `599`. The builder
requires a non-blank `path`.

```java
ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitStrategy(HttpWaitStrategy.builder()
        .port(8080)
        .path("/health")
        .statusRange(200, 200)            // exactly 200
        .build())
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

Matching uses `Matcher.matches()` against each newline-terminated raw log line, with
`Pattern.DOTALL` enabled. Use `.*pattern.*` for a substring match. A bare `pattern` does not match
a newline-terminated line because the trailing newline is part of the matched input; it can only
match a final, non-newline-terminated line.

## Composing wait strategies

Multiple strategies can be combined on the builder:

```java
ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitForHttpResponse(8080)
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

ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitStrategy(Wait.anyOf(
        PortWaitStrategy.builder().port(8080).build(),
        LogWaitStrategy.builder().pattern(".*fallback ready.*").build()
    ))
    .build();
```

## How wait strategies are evaluated

1. Strategies are checked in a polling loop. The poll interval starts at 10 ms and doubles up to a 500 ms maximum (configurable via `altcontainers.container.startup.readiness.poll.initial.ms` and `altcontainers.container.startup.readiness.poll.max.ms`).
2. Every poll evaluates the strategies in configuration order, stopping at the first unsatisfied one. A satisfied strategy stays in the set, so it is rechecked on the next poll until all of them are satisfied.
3. If the `startupTimeout` elapses before all strategies are satisfied, startup fails.
4. Failed startups are retried (if `startupAttempts > 1`) with fresh strategy state — log-match counters are reset for each attempt.

## Custom wait strategies

`WaitStrategy` is an open interface. Implement it directly for custom strategies, or compose
built-in strategies with `Wait.allOf()` / `Wait.anyOf()`. Custom strategies should be thread-safe,
idempotent for the same container state, and should not mutate the container or start threads.

```java
ContainerSpec.builder("my-service")
    // Lambda implementation
    .waitStrategy(container -> {
        Integer port = container.hostPort(8080);
        return port != null && port > 0;
    })
    // Direct construction of built-in types
    .waitStrategy(PortWaitStrategy.builder().port(8080).build())
    .waitStrategy(HttpWaitStrategy.builder().port(8080).path("/health").build())
    // Factory convenience
    .waitStrategy(Wait.forListeningPort(8080))
    .waitStrategy(Wait.forLogMessage(".*started.*", 1))
    .build();
```

A strategy that throws a `RuntimeException` fails the current startup attempt; the exception is
wrapped in `ContainerException` and the usual retry/destroy behavior applies.

## Learn next

- [Container Lifecycle](container-lifecycle)
- [Networking](networking)
