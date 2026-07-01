---
title: Wait Conditions
description: Configuring readiness conditions for Altcontainers containers.
---

# Wait Conditions

Wait conditions tell Altcontainers when a container is ready to use. They are configured on `ContainerSpec.Builder` and evaluated during startup.

## Available wait conditions

### Port wait

Waits until the mapped host port accepts a TCP connection.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForContainerPort(8080)
    .build();
```

The port must also be exposed via `.exposePorts()`. This condition is satisfied as soon as Docker's userland proxy binds the host port — it does not mean the service inside the container is ready to handle requests.

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

## Composing wait conditions

Multiple wait conditions can be combined:

```java
ContainerSpec.builder("my-service")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")
    .waitForLogMessage(".*started.*")
    .build();
```

All conditions must be satisfied before the container is considered ready.

## How wait conditions are evaluated

1. Conditions are checked in a polling loop with 250ms intervals (plus random jitter).
2. Satisfied conditions are removed from the pending set — they are never rechecked.
3. If the `startupTimeout` elapses before all conditions are satisfied, startup fails.
4. Failed startups are retried (if `startupAttempts > 1`) with fresh condition state — log-match counters are reset for each attempt.

## Custom wait conditions

The `WaitCondition` type is a sealed class with three permitted subtypes: `PortWait`, `HttpWait`, and `LogWait`. Custom conditions are not currently supported through the public API.

## Learn next

- [Container Lifecycle](container-lifecycle)
- [Networking](networking)
