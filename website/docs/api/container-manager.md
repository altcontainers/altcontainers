---
title: ContainerManager
description: The singleton entry point for Altcontainers container lifecycle operations.
---

# ContainerManager

`ContainerManager` is the primary entry point for creating and destroying Docker containers.

```java
package org.altcontainers.api;

public final class ContainerManager {
    public static ContainerManager getInstance();
    public Container createContainer(ContainerSpec spec);
    public void destroyContainer(Container container);
}
```

## Getting the instance

```java
ContainerManager manager = ContainerManager.getInstance();
```

`ContainerManager` is a singleton. Always use `getInstance()` — do not attempt to construct it directly.

## Creating a container

```java
ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80, "/")
    .build();

Container container = ContainerManager.getInstance().createContainer(spec);
```

`createContainer(spec)`:
- Validates the specification (`startupAttempts >= 1`, `startupTimeout` is positive)
- Pulls the Docker image if not cached
- Creates and starts the container
- Attaches a log stream
- Waits for all configured wait conditions
- Retries on failure if `startupAttempts > 1`

**Throws:**
- `IllegalArgumentException` if `startupAttempts < 1` or `startupTimeout` is not positive
- `NullPointerException` if `spec` is `null`
- `ContainerException` if the container cannot be started or does not become ready

## Destroying a container

```java
ContainerManager.getInstance().destroyContainer(container);
```

Or use try-with-resources:

```java
try (Container container = ContainerManager.getInstance().createContainer(spec)) {
    // ... use container ...
}
```

`destroyContainer(container)`:
- Stops and removes the container
- Blocks until Docker confirms removal
- Idempotent — safe to call multiple times
- No-op if `container` is `null`

## Learn next

- [ContainerSpec](container-spec)
- [Container](container)
- [Javadoc](javadocs)
