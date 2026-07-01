---
title: Project Setup
description: Set up a Java project with Altcontainers for container-based integration tests.
---

# Project Setup

## Requirements

- Java 17 or newer
- Maven 3.9+ or Gradle 8+
- Docker daemon running and accessible

## Maven project

Add the Altcontainers dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.0.1</version>
  <scope>test</scope>
</dependency>
```

Altcontainers is typically a test-scoped dependency since container management is used in integration tests.

## Gradle project

Add to `build.gradle`:

```groovy
dependencies {
    testImplementation 'org.altcontainers:core:0.0.1'
}
```

## Docker configuration

Altcontainers uses the `docker-java` library, which auto-detects the Docker daemon:

- **Linux/macOS:** Connects via the default Unix socket (`/var/run/docker.sock`)
- **Windows:** Connects via named pipe
- **Override:** Set the `DOCKER_HOST` environment variable for remote Docker daemons

Verify Docker is accessible:

```bash
docker info
```

If this command succeeds, Altcontainers will be able to communicate with the daemon.

## Altcontainers has no dependencies

The `org.altcontainers:core` JAR is a shaded uber-JAR. All transitive dependencies (docker-java, Jackson, Guava, Apache HttpClient, BouncyCastle, JNA, JSpecify) are relocated into the `nonapi.org.altcontainers.*` namespace. You do not need to add them to your project.

## Verify your setup

```java
import org.altcontainers.api.ContainerManager;
import org.altcontainers.api.ContainerSpec;

class SetupVerification {
    public static void main(String[] args) {
        try (var container = ContainerManager.getInstance().createContainer(
                ContainerSpec.builder("hello-world:latest").build())) {
            System.out.println("Container started: " + container.id());
        }
    }
}
```

If this runs without errors, your setup is correct.

## Learn next

- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
- [API Reference: ContainerManager](../api/container-manager)
