---
title: Network
description: Runtime handle to a Docker bridge network for container-to-container communication.
---

# Network

`Network` is a runtime handle to an ephemeral Docker bridge network. It implements `AutoCloseable` for try-with-resources cleanup.

```java
package org.altcontainers.api;

public final class Network implements AutoCloseable {
    public Network(String name, String id);
    public String id();
    public void destroy();
    public void close();
}
```

## Getting a network

Networks are created via `NetworkManager.createNetwork()`:

```java
try (Network network = NetworkManager.getInstance().createNetwork()) {
    // ... use network ...
}
```

## Using networks with containers

```java
try (Network network = NetworkManager.getInstance().createNetwork()) {
    ContainerSpec serverSpec = ContainerSpec.builder("my-server:latest")
        .network(network, "server")
        .build();

    ContainerSpec clientSpec = ContainerSpec.builder("my-client:latest")
        .network(network, "client")
        .build();

    try (Container server = ContainerManager.getInstance().createContainer(serverSpec);
         Container client = ContainerManager.getInstance().createContainer(clientSpec)) {
        // "client" can reach "server" via the network
    }
}
```

## Methods

### `id()`

Returns the Docker-assigned network identifier. Package-private `name()` is used internally as the network mode string.

### `destroy()`

Removes the Docker network, blocking until Docker confirms it is gone. Idempotent.

### `close()`

Delegates to `destroy()`. Implements `AutoCloseable`.

## Thread safety

`Network` instances are immutable handles. Multiple threads can safely call methods on the same `Network` instance.

## Learn next

- [NetworkManager](network-manager)
- [ContainerSpec](container-spec)
- [Javadoc](javadocs)
