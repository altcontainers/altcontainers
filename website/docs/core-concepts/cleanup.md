---
title: Cleanup
description: How Altcontainers ensures containers and networks are cleaned up.
---

# Cleanup

Altcontainers provides multiple layers of cleanup to ensure containers and networks do not leak.

## Explicit cleanup (try-with-resources)

`Container` and `Network` both implement `AutoCloseable`. The recommended pattern:

```java
try (Network network = Network.create()) {
    ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
        .exposePorts(80)
        .network(network, "nginx")
        .waitForHttpResponse(80, "/")
        .build();
    try (Container container = Container.create(containerSpec)) {
        // ... use container and network ...
    }
}
```

Resources are destroyed in reverse order (container first, then network).

## Manual cleanup

If try-with-resources is not suitable:

```java
Container container = Container.create(spec);
try {
    // ... use container ...
} finally {
    container.destroy();  // or container.close()
}
```

Or via the manager:

```java
Container.destroy(container);
```

## Idempotent destruction

Destroying an already-destroyed container or network is safe. Passing `null` is a no-op. All destroy methods block until Docker confirms removal.

## Automatic cleanup (Reaper)

Altcontainers starts a background "reaper" that registers a JVM shutdown hook. On JVM exit (including `System.exit()`), the reaper destroys:

- All containers created during the JVM session
- All networks created during the JVM session

The reaper is a safety net. You should still explicitly clean up resources when possible; relying solely on the reaper can cause resource exhaustion in long-running processes.

## Cleanup after startup failure

If a container fails to start (timeout, pull error, Docker error):

1. The partially created container is destroyed immediately.
2. If `startupAttempts > 1`, the next attempt begins after a backoff delay.
3. Cleanup failures are added as suppressed exceptions on the primary failure.

## Learn next

- [Guides: Log Consumers](../guides/log-consumers)
- [Guides: Retry and Backoff](../guides/retry-and-backoff)
