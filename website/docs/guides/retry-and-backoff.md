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
    .waitForHttpResponse(8080)
    .startupAttempts(3)                        // try up to 3 times
    .startupTimeout(Duration.ofSeconds(60))    // 60s for each phase of an attempt
    .build();
```

- `startupAttempts` — total number of startup attempts (default: `1`, no retries)
- `startupTimeout` — timeout used for the startup check and for the readiness wait, separately
  (default: 60 seconds)

Because the startup check and readiness wait each use `startupTimeout`, one attempt can take up to
roughly twice that value, plus the `prepare` hook and cleanup.

## Backoff algorithm

Between attempts, Altcontainers sleeps with linear backoff. Attempts are numbered from 1, and the
sleep happens after a failure and before the next attempt:

```
attempt 1: immediate
attempt 2: sleep 1 x multiplier after attempt 1 fails
attempt 3: sleep 2 x multiplier after attempt 2 fails
attempt 4: sleep 3 x multiplier after attempt 3 fails
...
```

The sleep duration is `altcontainers.container.startup.retry.backoff.multiplier.ms` × the failed
attempt number (default 1 second), capped at
`altcontainers.container.startup.retry.backoff.max.ms` (default 5 seconds).

## What happens on failure

1. The failed container is immediately destroyed.
2. If more attempts remain, the backoff sleep begins.
3. Fresh wait-condition state is created for the next attempt — log-match counters reset.
4. If all attempts fail, the last `ContainerException` is thrown.

A `RuntimeException` during startup (including from the `prepare` hook) is wrapped in
`ContainerException` and retried. An `Error` (such as a failed assertion) destroys the attempt's
container and propagates immediately without further attempts.

Image pulling happens before the retry loop. A pull failure or pull timeout is not retried by
`startupAttempts`.

## When to use retries

- **Flaky images** that occasionally fail to start within the timeout
- **Resource-constrained environments** where container startup is slower than usual
- **Race conditions** in container initialization scripts

## When not to use retries

- **Configuration errors** — retrying won't fix a wrong port or invalid command
- **Missing images** — retrying won't fix a typo in the image name; the pull fails before the first attempt
- **Fast feedback loops** — retries add latency; for development, fail fast

## Learn next

- [Troubleshooting](troubleshooting)
- [Container Lifecycle](../core-concepts/container-lifecycle)
- [Configuration](configuration)
