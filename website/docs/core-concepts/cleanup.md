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
    container.close();
}
```

Or via the static method:

```java
Container.close(container);
```

## Idempotent destruction

Destroying an already-destroyed container or network is safe. Passing `null` is a no-op.

## Automatic cleanup (Reaper)

Altcontainers launches a separate **reaper process** for each JVM session. The reaper is a standalone Java process that watches a persistent TCP connection for liveness. When the connection drops, the reaper cleans up all Docker resources labeled with the session ID.

### How the reaper works

1. **Session creation**: When the first container or network is created, the core module generates a unique session UUID.

2. **Reaper launch**: The core module extracts the reaper JAR from its classpath and launches it as a detached process (using `setsid` on Linux, `nohup` on other platforms). The reaper process receives the session ID via a system property.

3. **Port discovery**: The reaper binds a server socket on localhost (random port) and writes the port to a discovery file in the temp directory.

4. **TCP connection**: The core module polls the discovery file, connects to the reaper, and performs a session ID handshake. This persistent TCP connection serves as a liveness signal.

5. **Resource labeling**: All containers and networks created during the session are labeled with the session ID.

6. **Cleanup trigger**: When the JVM exits (including `System.exit()`), the JVM shutdown hook sends a `TERMINATE` message to the reaper and closes the TCP connection. The reaper then cleans up all Docker resources labeled with the session ID.

7. **Grace period**: If the connection drops unexpectedly (without `TERMINATE`), the reaper waits 30 seconds before cleanup, allowing for transient reconnections.

### Key characteristics

- **Separate process**: The reaper runs in its own JVM, independent of the application JVM. This ensures cleanup even if the application JVM crashes or is killed.
- **Per-session**: Each JVM session gets its own reaper process with a unique session ID.
- **No Docker sidecar**: Unlike Testcontainers' Ryuk, the reaper is a plain Java process, not a Docker container. No privileged sidecar is required.
- **Liveness-based**: The reaper watches a TCP connection. When the connection drops, cleanup begins.
- **Asynchronous with bounded retries**: Cleanup commands are enqueued for asynchronous processing. Each resource cleanup is retried up to a configurable maximum number of attempts with exponential backoff, so transient Docker daemon failures no longer cause permanent resource leaks.

The reaper is a safety net. You should still explicitly clean up resources when possible; relying solely on the reaper can cause resource exhaustion in long-running processes.

### Retry behavior

When a cleanup operation fails (e.g., a network has active endpoints), the reaper retries with exponential backoff. Each resource follows an escalation ladder:

1. **Graceful stop + remove** (containers): Attempts a graceful stop with a timeout, then removes the container.
2. **Force remove** (containers): If the graceful path fails, force-removes the container.
3. **Network remove**: Attempts to remove the network.
4. **Network force-remove** (after threshold): If the network cannot be removed after several attempts (e.g., due to active endpoints), disconnects all endpoints with force and then removes the network.

If all attempts are exhausted, the resource remains labeled for manual recovery via `docker ps -a --filter label=...`.

### Configuration

The reaper's retry behavior can be tuned via a system property on the application JVM:

| Property | Default | Description |
|----------|---------|-------------|
| `altcontainers.reaper.cleanup.max.attempts` | `5` | Maximum cleanup attempts per resource before giving up. Must be >= 1. |

Example:

```bash
java -Daltcontainers.reaper.cleanup.max.attempts=10 -jar myapp.jar
```

This property is automatically forwarded to the reaper process.

## Cleanup after startup failure

If a container fails to start (timeout, pull error, Docker error):

1. The partially created container is destroyed immediately.
2. If `startupAttempts > 1`, the next attempt begins after a backoff delay.
3. Cleanup failures are added as suppressed exceptions on the primary failure.

## Learn next

- [Guides: Log Consumers](../guides/log-consumers)
- [Guides: Retry and Backoff](../guides/retry-and-backoff)
