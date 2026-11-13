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
- Network creation fails
- Docker daemon communication errors

Closing a container or network does not throw `ContainerException`: if Docker fails or times out
removing it, the resource is handed to the reaper for asynchronous cleanup instead.

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
