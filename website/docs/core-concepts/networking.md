---
title: Networking
description: Managing Docker bridge networks for container-to-container communication with Altcontainers.
---

# Networking

Altcontainers provides `Network` for creating ephemeral Docker bridge networks. Containers on the same network can resolve each other by alias.

## Create a network

```java
try (Network network = Network.create()) {
    // Containers can join this network
}
```

Networks implement `AutoCloseable` and should be used with try-with-resources for deterministic cleanup.

## Attach containers to a network

```java
try (Network network = Network.create()) {
    ContainerSpec serverSpec = ContainerSpec.builder("my-server:latest")
        .exposePorts(8080)
        .network(network, "server")  // alias "server"
        .waitForHttpResponse(8080)
        .build();

    ContainerSpec clientSpec = ContainerSpec.builder("my-client:latest")
        .network(network, "client")  // alias "client"
        .build();

    try (Container server = Container.create(serverSpec);
         Container client = Container.create(clientSpec)) {
        // client can reach server at http://server:8080
    }
}
```

The `.network(network, alias)` method:
- Joins the container to the Docker bridge network at creation time
- Assigns the given alias for DNS resolution
- Calling it more than once replaces the previous network and aliases

Containers join a network when they are created; Altcontainers does not expose separate
attach/detach operations. Close containers before closing their network.

## Network naming

Networks are named with the pattern `altcontainers-<session-prefix>-<random>` (an 8-character prefix of the session UUID plus an 8-character random suffix). This ensures unique names even under concurrent use.

## Cleanup

- `network.close()` removes the network
- Networks are also cleaned up by the reaper on JVM exit
- Network destruction is idempotent; if Docker reports that endpoints are still attached, `close()`
  delegates the removal to the reaper, which retries and force-disconnects remaining endpoints
- Closing a network blocks until Docker confirms removal or the bounded removal attempt times out

## Learn next

- [Resource Limits](resource-limits)
- [Cleanup](cleanup)
- [API: Network](../api/network)
