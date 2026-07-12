---
title: Network
description: Runtime handle to a Docker bridge network for container-to-container communication.
---

# Network

`Network` is a runtime handle to an ephemeral Docker bridge network. It implements `AutoCloseable` for try-with-resources cleanup.

```java
package org.altcontainers.api;

public interface Network extends AutoCloseable {
    static Network create();
    static void close(Network network);
    String name();
    String id();
    void close();
}
```

## Creating a network

Networks are created via the static factory method `Network.create()`:

```java
try (Network network = Network.create()) {
    // ... use network ...
}
```

Creates a new Docker bridge network with a unique name (`altcontainers-<session>-<random>`). The network is registered with the reaper for automatic cleanup.

**Throws:** `ContainerException` if Docker fails to create the network.

## Closing a network

```java
Network.close(network);
```

Or use try-with-resources:

```java
try (Network network = Network.create()) {
    // ... use network ...
}
```

`close(network)`:
- Removes the Docker network
- Blocks until Docker confirms removal
- Retries transient `endpoint still attached` failures
- Idempotent
- No-op if `network` is `null`

## Using networks with containers

```java
try (Network network = Network.create()) {
    ContainerSpec serverSpec = ContainerSpec.builder("my-server:latest")
        .network(network, "server")
        .build();

    ContainerSpec clientSpec = ContainerSpec.builder("my-client:latest")
        .network(network, "client")
        .build();

    try (Container server = Container.create(serverSpec);
         Container client = Container.create(clientSpec)) {
        // "client" can reach "server" via the network
    }
}
```

## Methods

### `name()`

Returns the network name.

```java
String name = network.name();
```

### `id()`

Returns the Docker-assigned network identifier.

```java
String id = network.id();
```

### `close()`

Destroys this network. Implements `AutoCloseable`.

```java
network.close();
```

## Thread safety

`Network` instances are immutable handles. Multiple threads can safely call methods on the same `Network` instance.

## Learn next

- [Container](container)
- [ContainerSpec](container-spec)
- [Javadoc](javadocs)
