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
        .waitForHttpResponse(8080, "/health")
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
- Joins the container to the Docker bridge network
- Assigns the given alias for DNS resolution
- Calling it more than once replaces the previous network and aliases

## Network naming

Networks are named with the pattern `altcontainers-<session-id>-<random>` where the session ID comes from the reaper session. This ensures unique names even under concurrent use.

## Cleanup

- `network.close()` or `network.destroy()` removes the network
- Networks are also cleaned up by the reaper on JVM exit
- Network destruction is idempotent and retries transient `endpoint still attached` failures

## Learn next

- [Resource Limits](resource-limits)
- [Cleanup](cleanup)
- [API: Network](../api/network)
