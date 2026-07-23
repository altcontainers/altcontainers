---
title: Configuration
description: Complete reference for all Altcontainers configuration keys, values, defaults, and precedence.
---

# Configuration

Altcontainers is highly configurable. This page documents every configuration key, its default value, type, purpose, and how to override it.

## Configuration precedence

Configuration values are resolved from multiple sources. For each value, the first non-empty source wins:

| Priority | Source | Description |
|---|---|---|
| 1 (highest) | Programmatic | `Altcontainers.configure(builder -> { ... })` |
| 2 | Environment variable | `ALTCONTAINERS_*` prefixed, derived from property key |
| 3 | User properties file | `~/.altcontainers.properties` |
| 4 | Classpath properties | Bundled `altcontainers.properties` in the JAR |
| 5 (lowest) | Hardcoded default | Built into the code |

### Environment variable naming

Environment variables are derived from property keys by:

1. Uppercasing all characters
2. Replacing `.` with `_`
3. Prefixing with `ALTCONTAINERS_` if not already prefixed

| Property Key | Environment Variable |
|---|---|
| `altcontainers.docker.host` | `ALTCONTAINERS_DOCKER_HOST` |
| `altcontainers.reaper.disabled` | `ALTCONTAINERS_REAPER_DISABLED` |
| `altcontainers.reaper.connection.timeout.ms` | `ALTCONTAINERS_REAPER_CONNECTION_TIMEOUT_MS` |
| `altcontainers.container.startup.timeout.ms` | `ALTCONTAINERS_CONTAINER_STARTUP_TIMEOUT_MS` |
| `altcontainers.wait.http.probe.timeout.ms` | `ALTCONTAINERS_WAIT_HTTP_PROBE_TIMEOUT_MS` |
| `altcontainers.networks.parallelism` | `ALTCONTAINERS_NETWORKS_PARALLELISM` |

### Properties files

Properties files use the standard Java properties format (one `key=value` per line, `#` comments):

```properties
# ~/.altcontainers.properties
altcontainers.reaper.disabled=false
altcontainers.container.startup.timeout.ms=120000
altcontainers.wait.http.probe.timeout.ms=5000
```

### Programmatic configuration

Set configuration before creating any containers or networks:

```java
import org.altcontainers.api.Altcontainers;
import java.time.Duration;

Altcontainers.configure(builder -> builder
    .reaperDisabled(false)
    .containerStartupTimeout(Duration.ofSeconds(120))
    .httpProbeTimeout(Duration.ofSeconds(5))
);
```

Pass `null` to clear programmatic configuration and fall back to environment variables and properties files:

```java
Altcontainers.configure(null);
```

---

## All configuration keys

### Docker host

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.docker.host` | String | `unix:///var/run/docker.sock` | System property, Environment variable | Docker daemon connection URI |

**Supported schemes:** `unix://`, `tcp://`, `http://`, `https://` (with JVM default SSL)

**System property:** `-Daltcontainers.docker.host=tcp://localhost:2375`

**Environment variable:** `ALTCONTAINERS_DOCKER_HOST` (also `DOCKER_HOST` as a standard fallback)

**Note:** This is a system property, not a configuration file property. It is forwarded to the reaper process automatically.

---

### Reaper

The reaper is a separate per-session process that cleans up Docker resources when the JVM exits. See [Cleanup](../core-concepts/cleanup) for architecture details.

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.reaper.disabled` | boolean | `false` | Programmatic, Environment, Properties file | Disable the reaper process entirely |
| `altcontainers.reaper.connection.timeout.ms` | Duration (ms) | `10000` | Programmatic, Environment, Properties file | TCP connection/handshake timeout between core and reaper |
| `altcontainers.reaper.startup.timeout.ms` | Duration (ms) | `10000` | Programmatic, Environment, Properties file | How long the core module waits for the reaper process to bind its port |
| `altcontainers.reaper.stop.timeout.ms` | Duration (ms) | `5000` | Programmatic, Environment, Properties file | Timeout when stopping containers during cleanup |

#### `altcontainers.reaper.disabled`

Disables the reaper process. When `true`, no reaper is launched and cleanup depends entirely on explicit `close()` calls or the JVM shutdown hook.

**Values:** `true`, `false`

**Programmatic:**
```java
Altcontainers.configure(builder -> builder.reaperDisabled(true));
```

**Properties file:**
```properties
altcontainers.reaper.disabled=true
```

**Environment variable:**
```bash
ALTCONTAINERS_REAPER_DISABLED=true
```

#### `altcontainers.reaper.connection.timeout.ms`

Timeout for the TCP connection and session ID handshake between the core module and the reaper process.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .reaperConnectionTimeout(Duration.ofSeconds(15))
);
```

**Properties file:**
```properties
altcontainers.reaper.connection.timeout.ms=15000
```

#### `altcontainers.reaper.startup.timeout.ms`

How long the core module waits for the reaper process to bind its server socket and write the port discovery file.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .reaperStartupTimeout(Duration.ofSeconds(15))
);
```

**Properties file:**
```properties
altcontainers.reaper.startup.timeout.ms=15000
```

#### `altcontainers.reaper.stop.timeout.ms`

Timeout passed to Docker when stopping containers during cleanup. Controls how long Docker waits for the container process to exit gracefully before killing it.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .reaperStopTimeout(Duration.ofSeconds(10))
);
```

**Properties file:**
```properties
altcontainers.reaper.stop.timeout.ms=10000
```

---

### Container lifecycle

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.container.startup.timeout.ms` | Duration (ms) | `60000` | Programmatic, Environment, Properties file | Default per-attempt readiness timeout |
| `altcontainers.container.startup.readiness.poll.initial.ms` | Duration (ms) | `10` | Programmatic, Environment, Properties file | Initial readiness poll interval |
| `altcontainers.container.startup.readiness.poll.max.ms` | Duration (ms) | `500` | Programmatic, Environment, Properties file | Maximum readiness poll interval |
| `altcontainers.container.startup.retry.backoff.multiplier.ms` | Duration (ms) | `1000` | Programmatic, Environment, Properties file | Startup retry backoff multiplier |
| `altcontainers.container.startup.retry.backoff.max.ms` | Duration (ms) | `5000` | Programmatic, Environment, Properties file | Startup retry backoff maximum |
| `altcontainers.container.put.archive.pipe.buffer.bytes` | int (bytes) | `65536` | Programmatic, Environment, Properties file | Put-archive pipe buffer size for file copies |

#### `altcontainers.container.startup.timeout.ms`

Default per-attempt readiness timeout. Can be overridden per-container via `ContainerSpec.Builder.startupTimeout()`.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .containerStartupTimeout(Duration.ofSeconds(120))
);
```

**Per-container override:**
```java
ContainerSpec.builder("my-image")
    .startupTimeout(Duration.ofSeconds(30))
    .build();
```

**Properties file:**
```properties
altcontainers.container.startup.timeout.ms=120000
```

#### `altcontainers.container.startup.readiness.poll.initial.ms`

Initial interval between readiness strategy polls. The poll interval starts at this value and increases up to the maximum.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .containerReadinessPollInitial(Duration.ofMillis(25))
);
```

**Properties file:**
```properties
altcontainers.container.startup.readiness.poll.initial.ms=25
```

#### `altcontainers.container.startup.readiness.poll.max.ms`

Maximum interval between readiness strategy polls. The poll interval increases from the initial value to this maximum.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .containerReadinessPollMax(Duration.ofMillis(1000))
);
```

**Properties file:**
```properties
altcontainers.container.startup.readiness.poll.max.ms=1000
```

#### `altcontainers.container.startup.retry.backoff.multiplier.ms`

Multiplier for linear backoff between startup attempts. The sleep duration before attempt N is `multiplier × N` milliseconds.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .containerStartupRetryBackoffMultiplier(Duration.ofMillis(2000))
);
```

**Properties file:**
```properties
altcontainers.container.startup.retry.backoff.multiplier.ms=2000
```

#### `altcontainers.container.startup.retry.backoff.max.ms`

Maximum backoff sleep duration between startup attempts. The backoff is capped at this value regardless of the attempt number.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .containerStartupRetryBackoffMax(Duration.ofSeconds(10))
);
```

**Properties file:**
```properties
altcontainers.container.startup.retry.backoff.max.ms=10000
```

#### `altcontainers.container.put.archive.pipe.buffer.bytes`

Buffer size in bytes for the pipe used when copying files into containers via `Container.copyFileToContainer()`. Increase this for large file copies.

**Values:** Positive integer (bytes)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .containerPutArchivePipeBufferBytes(131072)
);
```

**Properties file:**
```properties
altcontainers.container.put.archive.pipe.buffer.bytes=131072
```

---

### Wait strategy probes

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.wait.port.probe.timeout.ms` | Duration (ms) | `500` | Programmatic, Environment, Properties file | TCP port probe socket connect timeout |
| `altcontainers.wait.http.probe.timeout.ms` | Duration (ms) | `2000` | Programmatic, Environment, Properties file | HTTP probe request timeout |

#### `altcontainers.wait.port.probe.timeout.ms`

Socket connect timeout for `PortWaitStrategy` TCP probes. Controls how long each probe waits for the port to accept a connection.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .portProbeTimeout(Duration.ofSeconds(1))
);
```

**Properties file:**
```properties
altcontainers.wait.port.probe.timeout.ms=1000
```

#### `altcontainers.wait.http.probe.timeout.ms`

Request timeout for `HttpWaitStrategy` HTTP/HTTPS probes. Controls how long each probe waits for an HTTP response.

**Values:** Positive integer (milliseconds)

**Programmatic:**
```java
Altcontainers.configure(builder -> builder
    .httpProbeTimeout(Duration.ofSeconds(5))
);
```

**Properties file:**
```properties
altcontainers.wait.http.probe.timeout.ms=5000
```

---

### Network

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.networks.parallelism` | int | `0` (unlimited) | System property only | Maximum concurrent network creation operations |

#### `altcontainers.networks.parallelism`

Bounds concurrent network creation to prevent Docker daemon overload under heavy parallel test execution. A value of `0` means no limit.

**Values:** Non-negative integer

**System property:**
```bash
-Daltcontainers.networks.parallelism=4
```

**Note:** This is a system property only. It is not available in properties files or programmatic configuration.

---

### Reaper internal (advanced)

These properties are used internally by the reaper process. They are automatically set by the core module when launching the reaper. Override only for advanced debugging.

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.reaper.log.directory` | String | `$(java.io.tmpdir)` | System property (reaper process) | Directory for reaper log files |
| `altcontainers.reaper.log.level` | String | `INFO` | System property (reaper process) | Reaper log level |

#### `altcontainers.reaper.log.directory`

Directory where the reaper writes its log files. The reaper writes rolling log files named `altcontainers-reaper-<sessionId>.log` in this directory. When unset, defaults to the Java temp directory (`java.io.tmpdir`), which is the same directory as the extracted reaper JAR.

**Values:** Valid directory path

**System property (reaper process):**
```bash
-Daltcontainers.reaper.log.directory=/tmp/reaper-logs
```

**Note:** This property is forwarded to the reaper process. Set it on the application JVM command line and it will be passed through. Only needed to override the default temp-directory location.

#### `altcontainers.reaper.log.level`

Log level for the reaper process.

**Values:** `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`

**System property (reaper process):**
```bash
-Daltcontainers.reaper.log.level=DEBUG
```

**Note:** This property is forwarded to the reaper process. Set it on the application JVM command line and it will be passed through.

---

## Complete default configuration

The bundled `altcontainers.properties` file contains all defaults:

```properties
# Altcontainers default configuration (classpath). Lowest precedence.
# Override per-user in ~/.altcontainers.properties or via ALTCONTAINERS_* env vars.
# Programmatic Altcontainers.configure() takes highest precedence.
# All time-oriented values are in milliseconds.

# --- Reaper (resource-cleanup watchdog) ---
altcontainers.reaper.disabled=false
altcontainers.reaper.connection.timeout.ms=10000
altcontainers.reaper.startup.timeout.ms=10000
altcontainers.reaper.stop.timeout.ms=5000

# --- Container lifecycle defaults ---
altcontainers.container.startup.timeout.ms=60000
altcontainers.container.startup.readiness.poll.initial.ms=10
altcontainers.container.startup.readiness.poll.max.ms=500
altcontainers.container.startup.retry.backoff.multiplier.ms=1000
altcontainers.container.startup.retry.backoff.max.ms=5000
altcontainers.container.put.archive.pipe.buffer.bytes=65536

# --- Wait strategy probes ---
altcontainers.wait.port.probe.timeout.ms=500
altcontainers.wait.http.probe.timeout.ms=2000
```

---

## Configuration methods summary

| Method | Scope | When to use |
|---|---|---|
| **Programmatic** | Application-wide | Set once at application startup; highest precedence |
| **Environment variable** | Process-wide | CI/CD pipelines, Docker environments, shell scripts |
| **User properties file** | Per-user | Developer workstation defaults (`~/.altcontainers.properties`) |
| **Classpath properties** | Per-project | Project-specific defaults bundled in the JAR |
| **System property** | Per-JVM | Docker host, network parallelism, reaper-internal settings |

---

## Validation

All configuration values are validated eagerly when first accessed. Invalid values fail fast with a `ContainerException`:

- **Duration values** must be positive (greater than zero)
- **Boolean values** must be `true` or `false`
- **Integer values** must be positive (greater than zero)
- **Docker host** must be a valid URI with a supported scheme

Malformed properties files or invalid values throw `ContainerException` immediately, not at container creation time.

---

## Learn next

- [Retry and Backoff](retry-and-backoff)
- [Troubleshooting](troubleshooting)
- [API: AltcontainersConfiguration](../api/intro)
