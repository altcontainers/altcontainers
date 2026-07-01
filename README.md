# Altcontainers

[![Java Version](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Altcontainers is a lightweight Java 17+ Docker container lifecycle management library.

## Quick Start

```java
ContainerSpec spec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80, "/")
    .build();

try (Container container = ContainerManager.getInstance().createContainer(spec)) {
    int port = container.hostPort(80);
    // use http://localhost:<port>
}
```

## Features

- **Shaded uber-JAR** — docker-java, Jackson, Guava, and other dependencies are relocated to prevent classpath conflicts
- **Automatic cleanup** — integrated reaper destroys containers and networks when the JVM exits
- **Readiness waiting** — port probes, HTTP response checks, log message matching, and custom conditions
- **Network management** — create and destroy Docker bridge networks
- **Retry with backoff** — configurable startup attempts with linear backoff

Altcontainers was originally developed as the container management layer for the [Paramixel](https://github.com/paramixel/paramixel) test orchestration framework.

Website: [https://www.altcontainers.org](https://www.altcontainers.org) (coming soon)
