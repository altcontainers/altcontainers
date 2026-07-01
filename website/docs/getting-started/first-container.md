---
title: Your First Container
description: Create, start, and clean up your first Docker container with Altcontainers.
---

# Your First Container

This guide walks through starting an nginx container, verifying it serves HTTP, and cleaning up.

## Create a container

```java
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerManager;
import org.altcontainers.api.ContainerSpec;

ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80, "/")
    .build();

try (Container container = ContainerManager.getInstance().createContainer(spec)) {
    // Container is running and ready
    int port = container.hostPort(80);
    System.out.println("Nginx is serving on port " + port);
}
```

## What happens

1. `ContainerSpec.builder("nginx:1.27")` creates a mutable builder for the nginx image.
2. `.exposePorts(80)` tells Docker to publish container port 80 to an ephemeral host port.
3. `.waitForHttpResponse(80, "/")` tells Altcontainers to wait until an HTTP GET to `/` returns a 2xx or 3xx status.
4. `.build()` produces an immutable `ContainerSpec`.
5. `ContainerManager.getInstance().createContainer(spec)` pulls the image (if needed), creates the container, starts it, and blocks until the HTTP wait condition is satisfied.
6. The try-with-resources block ensures the container is destroyed when the block exits.

## Add log output

```java
import org.altcontainers.api.PrefixConsumer;

ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .logConsumer(PrefixConsumer.of("NGINX", "nginx:1.27"))
    .waitForHttpResponse(80, "/")
    .build();
```

Container logs are printed to stdout as `[NGINX] nginx:1.27 | <log line>`.

## Use a custom command

```java
ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .command("nginx", "-g", "daemon off;")
    .exposePorts(80)
    .waitForLogMessage(".*start worker process.*")
    .build();
```

The `.command()` method appends arguments to the container's entrypoint.

## Learn next

- [Project Setup](project-setup)
- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
- [Core Concepts: Wait Conditions](../core-concepts/wait-conditions)
