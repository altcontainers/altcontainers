---
title: Container Lifecycle
description: Understanding the Altcontainers container lifecycle from creation to destruction.
---

# Container Lifecycle

Altcontainers manages the full Docker container lifecycle through `Container.create()` and `Container.destroy()`.

## Lifecycle stages

```
pull image → create container → start container → wait for readiness → use container → destroy container
```

### 1. Pull image

`Container.create(spec)` pulls the Docker image if it is not already cached locally. Image pulls are idempotent — if the image exists, the pull is skipped.

### 2. Create container

A Docker container is created from the image with the configuration in `ContainerSpec`: ports, mounts, network, environment, resource limits.

### 3. Start container

The container is started. Altcontainers immediately attaches a log stream to capture container output.

### 4. Wait for readiness

Altcontainers blocks until all configured wait conditions are satisfied, or the startup timeout elapses. Wait conditions include:
- **Port wait** — TCP connection accepted on the mapped host port
- **HTTP wait** — HTTP/HTTPS response status in an acceptable range
- **Log wait** — a log message matching a regex appears the required number of times

If the container fails to become ready, it is destroyed and the startup is retried (if `startupAttempts > 1`).

### 5. Use container

The returned `Container` handle provides:
- `id()` — Docker container ID
- `image()` — Docker image name
- `isRunning()` — whether the container is running
- `hostPort(containerPort)` — the mapped host port

### 6. Destroy container

The container is stopped and removed. Destruction is:
- **Idempotent** — safe to call multiple times
- **Blocking** — returns only when Docker confirms removal
- **Available via try-with-resources** — `Container` implements `AutoCloseable`

## Example

```java
ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80, "/")
    .startupTimeout(Duration.ofSeconds(30))
    .startupAttempts(2)
    .build();

try (Container container = Container.create(spec)) {
    int port = container.hostPort(80);
    // ... use container ...
} // container is destroyed here
```

## Startup retries

By default, `startupAttempts` is `1` (no retries). Set to `2` or higher to retry on `ContainerException` with linear backoff:

```
attempt 0: immediate
attempt 1: sleep 1s, then retry
attempt 2: sleep 2s, then retry
...
```

## Manual cleanup

If try-with-resources is not suitable:

```java
Container container = Container.create(spec);
try {
    // ... use container ...
} finally {
    Container.destroy(container);
}
```

Or call `container.destroy()` or `container.close()` directly.

## Automatic cleanup (Reaper)

Altcontainers registers a JVM shutdown hook (the "reaper") that destroys all containers and networks created during the JVM session, even if `System.exit()` is called. You should still explicitly clean up resources when possible; the reaper is a safety net.

## Learn next

- [Wait Conditions](wait-conditions)
- [Networking](networking)
- [Cleanup](cleanup)
