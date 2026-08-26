---
title: Project Setup
description: Set up a Java project with Altcontainers for container-based integration tests.
---

# Project Setup

## Requirements

- Java 17 or newer
- Maven 3.9+
- Docker daemon running and accessible

## Maven project

Add the Altcontainers dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.4.0</version>
  <scope>test</scope>
</dependency>
```

Altcontainers is typically a test-scoped dependency since container management is used in integration tests.

## Docker configuration

Altcontainers uses a shaded [docker-java](https://github.com/docker-java/docker-java) client. Docker host
configuration uses the following precedence:

1. Java system property `altcontainers.docker.host` (e.g., `-Daltcontainers.docker.host=tcp://localhost:2375`)
2. Environment variable `DOCKER_HOST`
3. Default `unix:///var/run/docker.sock`

Supported schemes: `unix://`, `tcp://`, `http://`, `https://` (with JVM default SSL).
Windows/named pipes, Docker context discovery, TLS cert envs, and private registry
 auth are not currently supported.

Verify Docker is accessible:

```bash
docker info
```

If this command succeeds, Altcontainers will be able to communicate with the daemon.

## Altcontainers has no dependencies

The `org.altcontainers:core` JAR is a shaded uber-JAR. All transitive dependencies
(docker-java, Jackson, Guava, Apache Commons, Bouncy Castle, JNA) are relocated into the
`nonapi.shaded.*` namespace; SLF4J is left unrelocated so your application can provide its
own binding. You do not need to add any of them to your project.

## Verify your setup

```java
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerSpec;

class SetupVerification {
    public static void main(String[] args) {
        try (var container = Container.create(
                ContainerSpec.builder("hello-world:latest").build())) {
            System.out.println("Container started: " + container.id());
        }
    }
}
```

If this runs without errors, your setup is correct.

## Learn next

- [Your First Container](first-container)
- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
