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
- Validates the specification before any Docker work begins (`startupAttempts >= 1`, `startupTimeout` is positive)
- Pulls the Docker image if it is not available locally (an image without a tag or digest is pulled as `latest`); concurrent pulls of the same image are deduplicated and bounded by `altcontainers.image.pull.timeout.ms`
- Creates and starts the container
- Attaches a log stream when an output listener or a log-based wait strategy is configured
- Runs the startup check, the optional `prepare` hook, and all configured wait conditions
- Retries on failure if `startupAttempts > 1`

**Throws:**
- `IllegalArgumentException` if `startupAttempts < 1` or `startupTimeout` is not positive — thrown before the image pull, so a misconfigured spec fails fast
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
- Attempts a graceful stop bounded by `altcontainers.reaper.stop.timeout.ms` (plus a short buffer), then removes the container
- If the stop times out or fails, force-removes the container synchronously (bounded at 10 seconds). If that also fails, or the JVM is shutting down or the close is interrupted, the container is handed to the reaper for asynchronous cleanup and `close()` returns
- Idempotent — safe to call multiple times
- No-op if `container` is `null`

Because of the stop and force-remove bounds, a close can block for roughly the configured stop
timeout plus 20 seconds before it delegates to the reaper.

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
    Integer port = container.hostPort(80);
    if (port != null) {
        // use the mapped port
    }
}
```

### `host()`

Returns the host clients should use to reach the container's published ports. This is the cached
post-start metadata when available, falling back to the host component of the configured Docker
host URI. Unix sockets and named pipes resolve to `localhost`.

```java
String host = container.host();
```

### `hostPort(containerPort)`

Returns the mapped host port for the given container port, or `null` if the port was not mapped.
The value is cached from the post-start Docker inspection; it is not re-queried on each call, so
external restarts or re-creations are not reflected.

```java
Integer hostPort = container.hostPort(80);
if (hostPort != null) {
    String url = "http://" + container.host() + ":" + hostPort;
}
```

### `copyFileToContainer(...)`

Copies a file into the container using the Docker archive API. `containerPath` is the destination
directory, `fileName` is the entry name, `content` is the file bytes, and `mode` is the POSIX file
mode (for example, `0644`). The file name must fit the POSIX ustar 100-byte name field.

```java
container.copyFileToContainer("/tmp", "config.json", content, 0644);
```

### `spec()`

Returns the immutable container spec used to create this container.

```java
ContainerSpec spec = container.spec();
```

### `close()`

Destroys this container. Implements `AutoCloseable`.

```java
container.close();
```

## Thread safety

`Container` instances are immutable handles. Methods that communicate with Docker use the shared
`DockerClient`. Multiple threads can safely call methods on the same `Container` instance;
`close()` is idempotent.

## Learn next

- [ContainerSpec](container-spec)
- [Network](network)
- [Javadoc](javadocs)
