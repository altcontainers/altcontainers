---
title: Log Consumers
description: Capturing and formatting Docker container log output with Altcontainers.
---

# Log Consumers

Altcontainers lets you capture container output through a `Consumer<OutputFrame>` configured via `ContainerSpec.Builder.onOutput()`.

## Prefixed log output

You can use a custom output consumer to format lines for display:

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .onOutput(frame -> System.out.println("[NGINX] nginx:1.27 | " + frame.utf8StringWithoutLineEnding()))
    .build();
```

Output:
```
[NGINX] nginx:1.27 | /docker-entrypoint.sh: Configuration complete; ready for start up
[NGINX] nginx:1.27 | 2024/01/01 00:00:00 [notice] 1#1: start worker processes
```

## Custom output consumers

Any `Consumer<OutputFrame>` can be used:

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-image")
    .onOutput(frame -> {
        String text = frame.utf8StringWithoutLineEnding();
        if (text.contains("ERROR")) {
            System.err.println("Container error: " + text);
        }
    })
    .build();
```

## Log stream lifecycle

The log stream is attached immediately after the container starts and disconnected when the container is destroyed. It runs on a daemon thread managed internally by `DockerClient`.

## Learn next

- [Retry and Backoff](retry-and-backoff)
- [Troubleshooting](troubleshooting)
