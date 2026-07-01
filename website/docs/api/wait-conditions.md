---
title: Wait Conditions
description: Readiness conditions for container startup — port probes, HTTP checks, and log matching.
---

# Wait Conditions

Wait conditions are configured through `ContainerSpec.Builder` methods. They are not directly instantiated from user code.

## Types

### PortWait

Satisfied when the mapped host port accepts a TCP connection.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForContainerPort(8080)
    .build();
```

### HttpWait

Satisfied when an HTTP/HTTPS GET returns a status in the acceptable range.

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")                    // 200..399
    .waitForHttpResponse(8080, "/health", 200, 200)          // exactly 200
    .waitForHttpsResponse(8443, "/health")                   // HTTPS, 200..399
    .build();
```

### LogWait

Satisfied when a log line matching a regex has appeared the required number of times.

```java
ContainerSpec.builder("my-image")
    .waitForLogMessage(".*started.*")       // one occurrence
    .waitForLogMessage(".*ready.*", 3)      // three occurrences
    .build();
```

## Evaluation

- Conditions are polled every ~250ms with random jitter
- Satisfied conditions are removed from the pending set
- All conditions must be satisfied before the `startupTimeout` elapses
- Log match counters reset between startup attempts

## Learn next

- [ContainerSpec](container-spec)
- [Core Concepts: Wait Conditions](../core-concepts/wait-conditions)
