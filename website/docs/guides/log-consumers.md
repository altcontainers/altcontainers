---
title: Log Consumers
description: Capturing and formatting Docker container log output with Altcontainers.
---

# Log Consumers

Altcontainers lets you capture container output through a `Consumer<OutputFrame>` configured via
`GenericContainerSpec.Builder.outputListener()`. A spec has at most one output listener; calling
`outputListener()` again replaces the previous listener. The listener receives both stdout and
stderr frames, distinguished by `OutputFrame.type()`.

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

A frame may contain multiple lines or a partial line, so the prefix is applied per frame, not
necessarily per line.

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

If a listener throws a `RuntimeException`, Altcontainers logs the failure and continues streaming;
the exception does not fail container startup or close the stream.

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
complete lines. `OutputFrame.Type` reports whether a frame came from stdout, stderr, a raw
stream, or an unrecognized stream type.

## Log stream lifecycle

The log stream is attached after the container starts, and only when a log-based wait strategy or
an output listener is configured. Without an output listener the stream is closed as soon as startup
completes, so container output stops being captured after readiness. With an output listener the
stream stays attached until the container is destroyed. Callbacks run on a background thread
managed by the Docker client.

## Learn next

- [Retry and Backoff](retry-and-backoff)
- [Troubleshooting](troubleshooting)
