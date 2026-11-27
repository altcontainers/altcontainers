---
title: ContainerException
description: Runtime exception for Altcontainers container lifecycle failures.
---

# ContainerException

`ContainerException` is a `RuntimeException` thrown when a container lifecycle operation fails.

```java
package org.altcontainers.api;

public class ContainerException extends RuntimeException {
    public ContainerException(String message);
    public ContainerException(String message, Throwable cause);
}
```

## When it is thrown

- Container creation fails (Docker daemon error)
- Container startup times out (wait conditions not satisfied)
- Container readiness waiting is interrupted
- Image pull fails or exceeds `altcontainers.image.pull.timeout.ms`
- Network creation fails
- The reaper process cannot be started or connected
- Configuration values are malformed or invalid (for example, in `altcontainers.properties`)
- Docker daemon communication errors

Invalid builder arguments (for example, a negative memory limit or a port outside `1..65535`)
throw `IllegalArgumentException` rather than `ContainerException`. `Container.create(spec)`
validates `startupTimeout` and `startupAttempts` before any Docker work and throws
`IllegalArgumentException` for invalid values.

Close operations report removal failures by handing the resource to the reaper for asynchronous
cleanup rather than throwing `ContainerException`.

It is an **unchecked exception** — callers are not required to catch it, but should be prepared for it in integration test cleanup (`@AfterEach`, `finally` blocks).

## Example

```java
try {
    Container container = Container.create(spec);
} catch (ContainerException e) {
    System.err.println("Failed to start container: " + e.getMessage());
    // e.getCause() may contain the underlying Docker error
}
```

## Learn next

- [Container](container)
- [Guides: Troubleshooting](../guides/troubleshooting)
