---
title: Wait Strategies
description: Readiness strategies for container startup — port probes, HTTP checks, log matching, and composition.
---

# Wait Strategies

Wait strategies tell Altcontainers when a container is ready. They implement the `WaitStrategy` interface and can be used through builder convenience methods, direct construction, or factory methods.

## Builder convenience methods

The simplest path: use the `ContainerSpec` builder (`GenericContainerSpec.Builder`) methods.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForContainerPort(8080)
    .build();
```

## Builders and constructors

The port, HTTP, and log strategies are public classes with public builders and public
constructors:

```java
WaitStrategy port = PortWaitStrategy.builder().port(8080).build();
WaitStrategy log = LogWaitStrategy.builder().pattern(".*started.*").build();
WaitStrategy http = HttpWaitStrategy.builder().port(8080).path("/health").build();

WaitStrategy port2 = new PortWaitStrategy(8080);
WaitStrategy log2 = new LogWaitStrategy(".*started.*", 1);
WaitStrategy http2 = new HttpWaitStrategy(HttpWaitStrategy.Protocol.HTTP, 8080, "/health", 200, 399);
```

The composite strategy classes are package-private; create them with `Wait.allOf(...)` and
`Wait.anyOf(...)`.

## Factory convenience

The `Wait` class provides discoverable static factory methods equivalent to direct construction:

```java
WaitStrategy port = Wait.forListeningPort(8080);
WaitStrategy log = Wait.forLogMessage(".*started.*", 1);
WaitStrategy http = Wait.forHttpResponse(HttpWaitStrategy.Protocol.HTTP, 8080, "/health", 200, 399);
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
    .waitStrategy(ready)
    .build();
```

## Custom strategies

Implement `WaitStrategy` directly for custom readiness logic:

```java
WaitStrategy portReady = container -> {
    Integer port = container.hostPort(8080);
    return port != null && port > 0;
};
```

Custom implementations should be thread-safe, idempotent for the same container state, and must
not mutate the container or start threads. A strategy that throws a `RuntimeException` fails the
current startup attempt.

Strategies that observe logs are fed raw log lines by the framework; the built-in `LogWaitStrategy`
matches newline-terminated raw lines using whole-string (`Matcher.matches()`) semantics. Use
`.*pattern.*` for a substring match.

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
    .exposePorts(8080, 8443)
    .waitForHttpResponse(8080)       // HTTP GET /, status 200..399
    .waitForHttpsResponse(8443)      // HTTPS GET /, 200..399 (trust-all)
    .build();
```

For a custom path or status range, construct `HttpWaitStrategy` directly. The `path` argument is a
literal URI path, not a path plus query string. For example, `/health?deep=true` is sent as
`/health%3Fdeep=true`, with no query. The builder requires a non-blank path; the default status
range is `200..399`, and explicit status values must be in `100..599`.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitStrategy(HttpWaitStrategy.builder()
        .port(8080)
        .path("/health")
        .statusRange(200, 200)       // exactly 200
        .build())
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

- Strategies are polled repeatedly; the poll interval starts at 10 ms and doubles up to a 500 ms maximum (configurable via `altcontainers.container.startup.readiness.poll.initial.ms` and `altcontainers.container.startup.readiness.poll.max.ms`)
- Each poll evaluates the strategies in configuration order and stops at the first unsatisfied one; satisfied strategies are rechecked on later polls
- All strategies must be satisfied before the readiness timeout (`startupTimeout`) elapses
- Stateful strategies (log matching) reset between startup attempts

## Learn next

- [ContainerSpec](container-spec)
- [Core Concepts: Wait Strategies](../core-concepts/wait-strategies)
