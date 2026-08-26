---
title: Installation
description: Add Altcontainers to a Java 17+ project.
---

# Installation

Altcontainers requires Java 17 or newer and a running Docker daemon.

## Maven

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.4.0</version>
</dependency>
```

## Prerequisites

- **Java 17+** — Altcontainers uses records, sealed classes, and other Java 17 language features.
- **Docker daemon** — Altcontainers communicates with Docker via the shaded [docker-java](https://github.com/docker-java/docker-java) client. The Docker daemon must be running and accessible (typically via the default Unix socket, `DOCKER_HOST` environment variable, or `altcontainers.docker.host` configuration).
- **No additional dependencies** — Altcontainers ships as a shaded uber-JAR. You do not need to add docker-java, Jackson, Guava, or any other transitive dependency.

## Verifying installation

```java
import org.altcontainers.api.Version;

public class CheckVersion {
    public static void main(String[] args) {
        System.out.println("Altcontainers version: " + Version.version());
    }
}
```

## Learn next

- [Your First Container](first-container)
- [Project Setup](project-setup)
