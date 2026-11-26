---
title: Project Setup
description: Set up a Java project with Altcontainers for container-based integration tests.
---

# Project Setup

## Requirements

- Java 17 or newer
- Maven 3.9+ (or any build tool that can consume Maven Central artifacts)
- Docker daemon running and accessible

## Add the dependency

Add the Altcontainers dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.5.0</version>
  <scope>test</scope>
</dependency>
```

Altcontainers is typically a test-scoped dependency because container management is used in
integration tests. [Installation](installation) shows the equivalent Gradle declaration.

## Docker configuration

Altcontainers uses docker-java's standard Docker configuration. The `altcontainers.docker.host`
setting (system property, `ALTCONTAINERS_DOCKER_HOST` environment variable, or properties file)
takes precedence when set; otherwise docker-java honors `DOCKER_HOST`, `DOCKER_TLS_VERIFY`,
`DOCKER_CERT_PATH`, `DOCKER_CONFIG`, and `DOCKER_CONTEXT`. See
[Configuration](../guides/configuration) for the complete precedence rules.

Altcontainers is tested on Linux. Windows/named-pipe setups are handled by docker-java's
transport but are not covered by CI. The reaper's detached launch uses `setsid`/`nohup`, which is
not supported on Windows; on Windows, set `altcontainers.reaper.disabled=true` and close
containers and networks explicitly.

Verify Docker is accessible with the same environment your tests will use:

```bash
docker info
```

If this command succeeds with the same Docker environment (and `altcontainers.docker.host` is not
pointing at a different daemon), Altcontainers will be able to communicate with the daemon.

## Shaded dependencies

The `org.altcontainers:core` JAR embeds and relocates its internal copies of docker-java,
Jackson, Guava, Apache Commons, Bouncy Castle, and JNA under `nonapi.shaded.*`. SLF4J is left
unrelocated so your application can provide its own binding. The published POM currently declares
`docker-java-core`, `docker-java-transport-zerodep`, and `slf4j-api` as compile dependencies;
they are resolved transitively, so you do not need to add them explicitly.

## Verify your setup

```java
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;

class SetupVerification {
    public static void main(String[] args) {
        try (var container = Container.create(
                ContainerSpec.builder("alpine:latest").command("sleep", "30").build())) {
            System.out.println("Container started: " + container.id());
        }
    }
}
```

The default startup check requires the container to still be running after Docker starts it, so
the sample keeps the container alive with `sleep 30`. A one-shot image such as `hello-world`
exits immediately and fails that check. If this sample runs without errors, your setup is correct.

## Learn next

- [Your First Container](first-container)
- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
- [Configuration](../guides/configuration)
