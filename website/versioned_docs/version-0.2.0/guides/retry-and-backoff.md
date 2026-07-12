---
title: Retry and Backoff
description: Configuring startup retries and backoff for flaky containers.
---

# Retry and Backoff

Altcontainers supports retrying container startup with linear backoff.

## Configuring retries

```java
ContainerSpec containerSpec = ContainerSpec.builder("my-flaky-image")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")
    .startupAttempts(3)                        // try up to 3 times
    .startupTimeout(Duration.ofSeconds(60))    // 60s per attempt
    .build();
```

- `startupAttempts` — total number of startup attempts (default: `1`, no retries)
- `startupTimeout` — per-attempt readiness timeout (default: 60 seconds)

## Backoff algorithm

Between attempts, Altcontainers sleeps with linear backoff:

```
attempt 0: immediate
attempt 1: sleep 1 second, then retry
attempt 2: sleep 2 seconds, then retry
attempt 3: sleep 3 seconds, then retry
...
```

The sleep duration is `backoffBaseMillis * attemptNumber` (1 second × attempt number).

## What happens on failure

1. The failed container is immediately destroyed.
2. If more attempts remain, the backoff sleep begins.
3. Fresh wait-condition state is created for the next attempt — log-match counters reset.
4. If all attempts fail, the last `ContainerException` is thrown.

## When to use retries

- **Flaky images** that occasionally fail to start within the timeout
- **Resource-constrained environments** where container startup is slower than usual
- **Race conditions** in container initialization scripts

## When not to use retries

- **Configuration errors** — retrying won't fix a wrong port or invalid command
- **Missing images** — retrying won't fix a typo in the image name
- **Fast feedback loops** — retries add latency; for development, fail fast

## Learn next

- [Troubleshooting](troubleshooting)
- [Container Lifecycle](../core-concepts/container-lifecycle)
