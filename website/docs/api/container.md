---
title: Container
description: Runtime handle to a started Docker container.
---

# Container

`Container` is a runtime handle to a started Docker container. It implements `AutoCloseable` for try-with-resources cleanup.

```java
package org.altcontainers.api;

public class Container implements AutoCloseable {
    public Container(String id, String image);
    public String id();
    public String image();
    public boolean isRunning();
    public int hostPort(int containerPort);
    public void destroy();
    public void close();
}
```

## Getting a container

Containers are created via `ContainerManager.createContainer(spec)`, not constructed directly:

```java
try (Container container = ContainerManager.getInstance().createContainer(spec)) {
    // ... use container ...
}
```

## Methods

### `id()`

Returns the Docker-assigned container identifier.

```java
String containerId = container.id();
```

### `image()`

Returns the Docker image name.

```java
String image = container.image();
```

### `isRunning()`

Returns `true` if the container exists and is in the `running` state. Absent containers return `false`.

```java
if (container.isRunning()) {
    int port = container.hostPort(80);
}
```

### `hostPort(containerPort)`

Returns the mapped host port for the given container port, or `-1` if not found.

```java
int hostPort = container.hostPort(80);
String url = "http://localhost:" + hostPort;
```

### `destroy()`

Stops and removes the container, blocking until Docker confirms it is gone. Idempotent.

```java
container.destroy();
```

### `close()`

Delegates to `destroy()`. Implements `AutoCloseable`.

## Thread safety

`Container` instances are immutable handles. Methods delegate to the shared `DockerClient` for all operations. Multiple threads can safely call methods on the same `Container` instance.

## Learn next

- [ContainerSpec](container-spec)
- [ContainerManager](container-manager)
- [Javadoc](javadocs)
