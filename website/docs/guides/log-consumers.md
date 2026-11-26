---
title: Log Consumers
description: Capturing and formatting Docker container log output with Altcontainers.
---

# Log Consumers

Altcontainers lets you capture container output through a `Consumer<OutputFrame>` configured via `ContainerSpec.Builder.outputListener()`.

## Prefixed log output

You can use a custom output consumer to format lines for display:

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .outputListener(frame -> System.out.println("[NGINX] nginx:1.27 | " + frame.safeUtf8StringWithoutLineEnding()))
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
    .outputListener(frame -> {
        String text = frame.safeUtf8StringWithoutLineEnding();
        if (text.contains("ERROR")) {
            System.err.println("Container error: " + text);
        }
    })
    .build();
```

## Stream type

`OutputFrame.type()` reports which Docker stream produced the chunk:

| Value | Meaning |
|---|---|
| `OutputFrame.Type.STDOUT` | Container standard output |
| `OutputFrame.Type.STDERR` | Container standard error |
| `OutputFrame.Type.RAW` | Raw stream with no stdout/stderr distinction |
| `OutputFrame.Type.UNKNOWN` | Stream type Docker did not report |

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-image")
    .outputListener(frame -> {
        if (frame.type() == OutputFrame.Type.STDERR) {
            System.err.println(frame.safeUtf8StringWithoutLineEnding());
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

The log stream is attached after the container starts, and only when a log-based wait strategy or
an output listener is configured. Without an output listener the stream is closed as soon as startup
completes, so container output stops being captured after readiness. With an output listener the
stream stays attached until the container is destroyed.

## Learn next

- [Retry and Backoff](retry-and-backoff)
- [Troubleshooting](troubleshooting)
