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
        .waitForHttpResponse(80)
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
- **Asynchronous with bounded retries**: Cleanup commands are enqueued for asynchronous processing. Each resource cleanup is retried up to a configurable maximum number of attempts with exponential backoff, and a session teardown re-discovers and retries leftovers in a bounded number of sweeps, so transient Docker daemon failures no longer cause permanent resource leaks.

The reaper is a safety net. You should still explicitly clean up resources when possible; relying solely on the reaper can cause resource exhaustion in long-running processes.

### Retry behavior

When a cleanup operation fails (e.g., a network has active endpoints), the reaper retries with exponential backoff. Each resource follows an escalation ladder:

1. **Graceful stop + remove** (containers): Attempts a graceful stop with a timeout, then removes the container. Anonymous volumes created by the container are removed with it; named volumes and bind mounts are never touched.
2. **Force remove** (containers): If the graceful path fails, force-removes the container (including its anonymous volumes).
3. **Network remove**: Attempts to remove the network.
4. **Network force-remove** (after threshold): If the network cannot be removed after several attempts (e.g., due to active endpoints), disconnects all endpoints with force and then removes the network.

### Verified sweeps

A session teardown is not a single pass. The reaper runs up to four sweeps; each sweep lists the
session's labeled containers, force-removes them, waits for that phase to drain (reserving time for
networks), then lists the session's networks afresh and removes them. If any resource is still
unresolved — its attempt ladder was exhausted — the reaper pauses briefly and sweeps again, which
re-reads the daemon's current state and restarts that resource's attempt ladder.

Sweeping stops as soon as nothing is unresolved, after four sweeps, or when the five-minute drain
deadline expires, whichever comes first. Resource listings are retried three times, so a transient
daemon error neither hides resources nor counts as "nothing to clean".

### When cleanup cannot complete

The reaper verifies the result before exiting. If labeled resources remain (or a listing kept
failing), it logs an error naming them with the label filter to find them:

```
ERROR … Session <id> cleanup incomplete after 4 sweep(s) (discoveryComplete=true):
containers=[<id>] networks=[]; resources remain labeled with altcontainers-containers.session-id=<id>
```

and exits with status `2` (`0` = session torn down, `1` = reaper failure). Resources are reported only
after every sweep and the whole drain deadline have been exhausted, so a non-zero status means the
daemon was persistently unable to remove them, not that a single attempt failed. They can be removed
manually with `docker ps -a --filter label=altcontainers-containers.session-id=<id>` and
`docker network ls --filter label=altcontainers-containers.session-id=<id>`.

### Configuration

The reaper's retry behavior can be tuned via a system property on the application JVM:

| Property | Default | Description |
|----------|---------|-------------|
| `altcontainers.reaper.cleanup.max.attempts` | `5` | Maximum cleanup attempts per resource per sweep before the sweep gives up on it. Must be >= 1. A resource is retried again in the next sweep, so the effective ceiling is `attempts × sweeps`, still bounded by the drain deadline. |

Example:

```bash
java -Daltcontainers.reaper.cleanup.max.attempts=10 -jar myapp.jar
```

This property is automatically forwarded to the reaper process.

## Cleanup after startup failure

If a container fails to start (timeout, pull error, Docker error):

1. The partially created container is destroyed immediately.
2. If `startupAttempts > 1`, the next attempt begins after a backoff delay.
3. Cleanup failures do not replace the startup failure: they are logged, and a stop or removal that times out is delegated to the reaper. The exception reported to the caller is always the startup failure.

## Learn next

- [Guides: Log Consumers](../guides/log-consumers)
- [Guides: Retry and Backoff](../guides/retry-and-backoff)
