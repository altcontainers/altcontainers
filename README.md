# Altcontainers

[![Build Status](https://github.com/altcontainers/altcontainers/actions/workflows/build.yaml/badge.svg)](https://github.com/altcontainers/altcontainers/actions)
[![Java Version](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Maven Central](https://img.shields.io/maven-central/v/org.altcontainers/core)](https://central.sonatype.com/search?namespace=org.altcontainers)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Altcontainers is a lightweight Java 17+ Docker container lifecycle management library.

## Requirements

- Java 17 or newer
- A reachable Docker daemon (Docker is required to build and test the project)

## Installation

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.5.0</version>
  <scope>test</scope>
</dependency>
```

See the [documentation](https://www.altcontainers.org/) for installation, configuration,
and usage guides.

## Docker Host Configuration

Altcontainers uses docker-java's standard Docker configuration. For the Docker host,
the first value found wins:

1. Java system property `altcontainers.docker.host` (e.g., `-Daltcontainers.docker.host=tcp://localhost:2375`)
2. Environment variable `ALTCONTAINERS_DOCKER_HOST`
3. `altcontainers.docker.host` in `~/.altcontainers.properties`
4. `altcontainers.docker.host` in the bundled classpath `altcontainers.properties` (empty by default)
5. Environment variable `DOCKER_HOST` (read by docker-java)
6. docker-java's default, `unix:///var/run/docker.sock`

At the Docker client level, docker-java supports `unix://`, `tcp://`, `http://`, `https://`,
and `npipe://` hosts. Altcontainers is tested on Linux; Windows/named-pipe setups are not
covered by CI, and the reaper's detached launch uses `setsid`/`nohup`, which is not supported on
Windows. On Windows, set `altcontainers.reaper.disabled=true` and close resources explicitly.

When `altcontainers.docker.host` is not set, docker-java also honors the standard
`DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH`, `DOCKER_CONFIG`, and `DOCKER_CONTEXT` settings.
Registry credentials for image pulls come from the standard Docker configuration
(`~/.docker/config.json` or `DOCKER_CONFIG`).

See [Configuration](https://www.altcontainers.org/guides/configuration) for the complete
key reference.

## Quick Start

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80)
    .build();

try (Container container = Container.create(containerSpec)) {
    int port = container.hostPort(80);
    // use http://localhost:<port>
}
```

## Features

- **Shaded uber-JAR** — docker-java, Jackson, Guava, Apache Commons, Bouncy Castle, and JNA are relocated under `nonapi.shaded.*` to isolate Altcontainers' internal copies from application dependencies; SLF4J is left unrelocated so applications can provide their own binding
- **Automatic cleanup** — integrated reaper destroys containers and networks when the JVM exits
- **Readiness waiting** — port probes, HTTP response checks, log message matching, and custom strategies
- **Network management** — create and destroy Docker bridge networks
- **Bind mounts** — read-write or read-only host directory mounts
- **Resource limits** — memory, CPU, `/dev/shm`, and Linux ulimits
- **Image pulling** — local image reuse, concurrent pull deduplication, and a configurable pull timeout
- **Retry with backoff** — configurable startup attempts with linear backoff

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

## Support

![YourKit logo](https://www.yourkit.com/images/yklogo.png)

[YourKit](https://www.yourkit.com/) supports open source projects with innovative and intelligent tools for monitoring and profiling Java and .NET applications.

YourKit is the creator of <a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>,
<a href="https://www.yourkit.com/dotnet-profiler/">YourKit .NET Profiler</a>,
and <a href="https://www.yourkit.com/youmonitor/">YourKit YouMonitor</a>.

---

Copyright (c) 2026-present Douglas Hoard
