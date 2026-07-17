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
    .onOutput(frame -> System.out.println("[NGINX] nginx:1.27 | " + frame.safeUtf8StringWithoutLineEnding()))
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
        String text = frame.safeUtf8StringWithoutLineEnding();
        if (text.contains("ERROR")) {
            System.err.println("Container error: " + text);
        }
    })
    .build();
```

## Safe vs. raw output

`OutputFrame` provides two families of text conversion methods:

| Method | Use case |
|---|---|
| `safeUtf8String()` / `safeUtf8StringWithoutLineEnding()` | Terminal display, text log files |
| `utf8String()` / `utf8StringWithoutLineEnding()` | Raw decoding, binary processing, fidelity-sensitive consumers |
| `bytes()` / `string(Charset)` | Byte-level access, non-UTF-8 charsets |

Safe methods remove NUL, unsafe control characters, DEL, and ANSI terminal escape sequences
(CSI and OSC) that can alter terminal state or cause tools like `grep` to classify captured output
as binary. They preserve printable Unicode, tabs, line feeds, and carriage returns.

Raw methods return the decoded frame payload without filtering. Use them when you need exact
output — for example, when processing binary data, matching exact byte sequences, or forwarding
frames to another system.

Frames are arbitrary chunks from Docker's log stream and are not guaranteed to represent
complete lines.

## Log stream lifecycle

The log stream is attached immediately after the container starts and disconnected when the container is destroyed. It runs on a daemon thread managed internally by `DockerClient`.

## Learn next

- [Retry and Backoff](retry-and-backoff)
- [Troubleshooting](troubleshooting)
