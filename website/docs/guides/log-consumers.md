---
title: Log Consumers
description: Capturing and formatting Docker container log output with Altcontainers.
---

# Log Consumers

Altcontainers lets you capture container log output through a `Consumer<String>` configured via `ContainerSpec.Builder.logConsumer()`.

## Prefixed log output

`PrefixConsumer` formats log lines for display on stdout:

```java
import org.altcontainers.api.PrefixConsumer;

ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .logConsumer(PrefixConsumer.of("NGINX", "nginx:1.27"))
    .build();
```

Output:
```
[NGINX] nginx:1.27 | /docker-entrypoint.sh: Configuration complete; ready for start up
[NGINX] nginx:1.27 | 2024/01/01 00:00:00 [notice] 1#1: start worker processes
```

Null or blank log lines are silently ignored.

## Custom log consumers

Any `Consumer<String>` can be used:

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-image")
    .logConsumer(line -> {
        if (line.contains("ERROR")) {
            System.err.println("Container error: " + line);
        }
    })
    .build();
```

## Log stream lifecycle

The log stream is attached immediately after the container starts and disconnected when the container is destroyed. It runs on a daemon thread managed internally by `DockerClient`.

## Learn next

- [Retry and Backoff](retry-and-backoff)
- [Troubleshooting](troubleshooting)
