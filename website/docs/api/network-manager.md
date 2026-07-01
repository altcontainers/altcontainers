---
title: NetworkManager
description: The singleton entry point for Altcontainers Docker network operations.
---

# NetworkManager

`NetworkManager` is the entry point for creating and destroying Docker bridge networks.

```java
package org.altcontainers.api;

public final class NetworkManager {
    public static NetworkManager getInstance();
    public Network createNetwork();
    public void destroyNetwork(Network network);
}
```

## Getting the instance

```java
NetworkManager manager = NetworkManager.getInstance();
```

## Creating a network

```java
Network network = NetworkManager.getInstance().createNetwork();
```

Creates a new Docker bridge network with a unique name (`altcontainers-<session>-<random>`). The network is registered with the reaper for automatic cleanup.

**Throws:** `ContainerException` if Docker fails to create the network.

## Destroying a network

```java
NetworkManager.getInstance().destroyNetwork(network);
```

Or use try-with-resources:

```java
try (Network network = NetworkManager.getInstance().createNetwork()) {
    // ... use network ...
}
```

`destroyNetwork(network)`:
- Removes the Docker network
- Blocks until Docker confirms removal
- Retries transient `endpoint still attached` failures
- Idempotent
- No-op if `network` is `null`

## Learn next

- [Network](network)
- [ContainerSpec](container-spec)
- [Javadoc](javadocs)
