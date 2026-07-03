# Altcontainers

[![Java Version](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Altcontainers is a lightweight Java 17+ Docker container lifecycle management library.

## Docker Host Configuration

Altcontainers connects to Docker using the following precedence:

1. Java system property `altcontainers.docker.host` (e.g., `-Daltcontainers.docker.host=tcp://localhost:2375`)
2. Environment variable `DOCKER_HOST`
3. Default `unix:///var/run/docker.sock`

Supported schemes: `unix://`, `tcp://`, `http://`, `https://` (with JVM default SSL).

Docker is required on the machine to build and test the project. Windows/named pipes,
Docker context discovery, Docker TLS cert envs, and private registry auth are
not currently supported.

## Quick Start

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .exposePorts(80)
    .waitForHttpResponse(80, "/")
    .build();

try (Container container = Container.create(containerSpec)) {
    int port = container.hostPort(80);
    // use http://localhost:<port>
}
```

## Features

- **Shaded uber-JAR** — OkHttp, Gson, Okio, and other dependencies are relocated to prevent classpath conflicts
- **Automatic cleanup** — integrated reaper destroys containers and networks when the JVM exits
- **Readiness waiting** — port probes, HTTP response checks, log message matching, and custom conditions
- **Network management** — create and destroy Docker bridge networks
- **Retry with backoff** — configurable startup attempts with linear backoff

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

# Support

![YourKit logo](https://www.yourkit.com/images/yklogo.png)

[YourKit](https://www.yourkit.com/) supports open source projects with innovative and intelligent tools for monitoring and profiling Java and .NET applications.

YourKit is the creator of <a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>,
<a href="https://www.yourkit.com/dotnet-profiler/">YourKit .NET Profiler</a>,
and <a href="https://www.yourkit.com/youmonitor/">YourKit YouMonitor</a>.

---

Copyright (c) 2026-present Douglas Hoard

