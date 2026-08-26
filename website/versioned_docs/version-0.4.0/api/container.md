---
title: Container
description: Runtime handle to a started Docker container.
---

# Container

`Container` is a runtime handle to a started Docker container. It implements `AutoCloseable` for try-with-resources cleanup.

```java
package org.altcontainers.api;

public interface Container extends AutoCloseable {
    static Container create(ContainerSpec containerSpec);
    static void close(Container container);
    String id();
    String image();
    boolean isRunning();
    String host();
    Integer hostPort(int containerPort);
    void copyFileToContainer(String containerPath, String fileName, byte[] content, int mode);
    ContainerSpec spec();
    void close();
}
```

## Creating a container

Containers are created via the static factory method `Container.create(spec)`:

```java
try (Container container = Container.create(spec)) {
    // ... use container ...
}
```

`create(spec)`:
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

## Closing a container

```java
Container.close(container);
```

Or use try-with-resources:

```java
try (Container container = Container.create(spec)) {
    // ... use container ...
}
```

`close(container)`:
- Stops and removes the container
- Blocks until Docker confirms removal
- Idempotent — safe to call multiple times
- No-op if `container` is `null`

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

### `host()`

Returns the Docker host.

```java
String host = container.host();
```

### `hostPort(containerPort)`

Returns the mapped host port for the given container port, or `null` if not mapped.

```java
Integer hostPort = container.hostPort(80);
if (hostPort != null) {
    String url = "http://localhost:" + hostPort;
}
```

### `copyFileToContainer(...)`

Copies a file into the container.

```java
copyFileToContainer("/tmp", "config.json", content, 0644);
```

### `spec()`

Returns the container spec used to create this container.

```java
ContainerSpec spec = container.spec();
```

### `close()`

Destroys this container. Implements `AutoCloseable`.

```java
container.close();
```

## Thread safety

`Container` instances are immutable handles. Methods delegate to the shared `DockerClient` for all operations. Multiple threads can safely call methods on the same `Container` instance.

## Learn next

- [ContainerSpec](container-spec)
- [Network](network)
- [Javadoc](javadocs)
