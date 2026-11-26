---
title: ContainerSpec
description: Immutable desired configuration for a Docker container, built with a fluent builder.
---

# ContainerSpec

`ContainerSpec` captures the desired configuration for a Docker container. It is an interface;
`ContainerSpec.builder(String)` returns a mutable `GenericContainerSpec.Builder`, and `build()`
produces an immutable `GenericContainerSpec`. Specs are consumed by `Container.create(spec)`.

## Builder example

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .command("nginx", "-g", "daemon off;")
    .exposePorts(80, 443)
    .network(network, "nginx")
    .waitForHttpResponse(80)
    .startupTimeout(Duration.ofSeconds(30))
    .startupAttempts(2)
    .outputListener(frame -> System.out.println("[NGINX] nginx:1.27 | " + frame.safeUtf8StringWithoutLineEnding()))
    .memory(256 * 1024 * 1024)
    .bindDirectory("/host/config", "/etc/nginx/conf.d", true)
    .ulimit("nofile", 65536, 65536)
    .environment(Map.of("TZ", "UTC"))
    .portBindings(Map.of(443, 8443))   // 443 must also be exposed
    .build();
```

## Builder semantics

Most configuration methods either accumulate values or replace the previously configured value:

- **Accumulating:** `command(...)`, `exposePorts(...)` (duplicate ports are ignored),
  `bindDirectory(...)`, `ulimit(...)`, `waitStrategy(...)`, `waitForContainerPort(...)`,
  `waitForHttpResponse(...)`, `waitForHttpsResponse(...)`, and `waitForLogMessage(...)`.
  Values are applied in call order.
- **Replacing:** `network(...)`, `workingDirectory(...)`, `outputListener(...)`, `prepare(...)`,
  `startupCheckStrategy(...)`, `startupTimeout(...)`, `startupAttempts(...)`, `memory(...)`,
  `memorySwap(...)`, `shmSize(...)`, `cpuShares(...)`, `cpuPeriod(...)`, `cpuQuota(...)`,
  `environment(...)`, and `portBindings(...)`.
- **Clearing:** `clearUlimits()` removes all configured ulimits.

`command(...)` sets the container command (Docker `CMD`), replacing the image's default command;
the image `ENTRYPOINT` still runs. When no command is configured, the image's default command is
used.

`toBuilder()` returns an independent, pre-filled builder. `with(customizer)` is a convenience
that derives a new spec from this one:

```java
ContainerSpec retrying = containerSpec.with(builder -> builder.startupAttempts(3));
```

`toBuilder()`/`with()` do not modify the original spec.

## Lifecycle hooks

- `outputListener(Consumer<OutputFrame>)` — receives raw output frames throughout the container's
  lifetime. Frames are unfiltered, undecoded chunks with stream-type metadata; Altcontainers does
  not line-buffer them. The listener is invoked on a background thread, and listener exceptions are
  logged rather than propagated.
- `prepare(Consumer<Container>)` — invoked after the container starts and port mappings are
  available, but before readiness checks begin. A thrown `RuntimeException` cancels the current
  startup attempt and is wrapped in `ContainerException`; an `Error` (such as a failed assertion)
  destroys the attempt's container and propagates without further attempts.
- `startupCheckStrategy(StartupCheckStrategy)` — evaluated after Docker starts the container and
  before readiness wait strategies run (default: the container must still be running).

## Environment variables

`environment(Map<String, String>)` replaces all previously configured environment variables. Keys
must be non-blank and values must be non-null.

```java
GenericContainerSpec.Builder builder = ContainerSpec.builder("my-image");
builder.environment(Map.of("MY_VAR", "value"));
```

## Fixed port bindings

`portBindings(Map<Integer, Integer>)` maps container ports to fixed host ports:

```java
ContainerSpec.builder("my-image")
    .exposePorts(8080)
    .portBindings(Map.of(8080, 9090))
    .build();
```

Every key must also be exposed through `exposePorts(...)`. A host-port value of `0` lets Docker
choose an ephemeral port. Exposed ports without a fixed binding are also assigned ephemeral host
ports.

## Other container settings

```java
builder.workingDirectory("/app");   // in-container working directory
builder.clearUlimits();             // remove all ulimits added so far
```

## Custom wait strategies

`waitStrategy(...)` accepts any `WaitStrategy` implementation: lambdas, built-in strategy
builders or constructors, or composed strategies.

```java
GenericContainerSpec.Builder builder = ContainerSpec.builder("my-image");

// Builder
builder.waitStrategy(HttpWaitStrategy.builder().port(8080).path("/health").build());

// Factory convenience
builder.waitStrategy(Wait.forListeningPort(8080));

// Composition
builder.waitStrategy(Wait.allOf(
    PortWaitStrategy.builder().port(8080).build(),
    LogWaitStrategy.builder().pattern(".*started.*").build()
));

// Custom lambda
builder.waitStrategy(container -> {
    Integer port = container.hostPort(8080);
    return port != null && port > 0;
});
```

All configured wait strategies must be satisfied. Log matching uses `Matcher.matches()` against
each newline-terminated raw log line, so use `.*pattern.*` for a substring match.

## Defaults

| Setting | Default |
|---|---|
| `startupTimeout` | `altcontainers.container.startup.timeout.ms` (60 seconds by default; `ContainerSpec.DEFAULT_STARTUP_TIMEOUT`) |
| `startupAttempts` | 1 (no retry) |
| `startupCheckStrategy` | `StartupCheckStrategy.isRunning()` |
| `command` | empty (image default command) |
| `exposedPorts` | empty |
| `bindMounts` | empty |
| `networkMode` / `networkAliases` | `null` / empty (Docker default network) |
| `workingDirectory` | `null` (image default) |
| `environment` | empty |
| `portBindings` | empty (ephemeral host ports) |
| `waitConditions` | empty |
| `ulimits` | empty |
| Memory, swap, `/dev/shm`, CPU values | 0 (no explicit limit; Docker defaults apply) |
| `outputListener` / `prepare` | `null` |

`startupTimeout` bounds the startup check and the readiness wait separately; a full attempt can
therefore take up to roughly twice that value, plus the `prepare` hook.

## Validation

The builder validates values as they are supplied and throws `IllegalArgumentException` for
invalid values; some methods also throw `NullPointerException` for null arguments (see the
Javadoc for the specific method). Invalid values include:

- blank image names, paths, aliases, ulimit names, or wait regexes
- container ports outside `1..65535`
- fixed host ports outside `0..65535`, or fixed bindings for ports that are not exposed
- negative memory, swap, `/dev/shm`, or CPU values
- ulimit limits below zero, or `soft > hard` when `hard > 0`
- fewer than one startup attempt
- blank environment keys or null environment values

`startupTimeout` is validated by `Container.create(spec)` and must be positive.

## Immutability

`ContainerSpec` instances are immutable and safe to share between threads. The
`GenericContainerSpec.Builder` is mutable and not thread-safe.

## Learn next

- [Container](container)
- [Wait Strategies](wait-strategies)
- [Javadoc](javadocs)
