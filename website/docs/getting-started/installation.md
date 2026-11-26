---
title: Installation
description: Add Altcontainers to a Java 17+ project.
---

# Installation

Altcontainers requires Java 17 or newer and a reachable Docker daemon.

## Maven

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.5.0</version>
</dependency>
```

Add `<scope>test</scope>` when Altcontainers is used only by tests.

## Gradle

```groovy
testImplementation("org.altcontainers:core:0.5.0")
```

## Prerequisites

- **Java 17+** — Altcontainers is compiled and tested against Java 17 and later.
- **Docker daemon** — Altcontainers communicates with Docker through the shaded
  [docker-java](https://github.com/docker-java/docker-java) client. The daemon must be running
  and reachable. See [Configuration](../guides/configuration) for Docker host resolution.
- **Shaded artifact** — the `core` JAR embeds and relocates docker-java, Jackson, Guava, Apache
  Commons, Bouncy Castle, and JNA under `nonapi.shaded.*`. The published POM currently also declares
  `docker-java-core`, `docker-java-transport-zerodep`, and `slf4j-api` as compile dependencies,
  so those coordinates are resolved transitively. You do not need to add them explicitly.
  SLF4J is intentionally left unrelocated so your application can provide the binding.

## Verifying installation

```java
import org.altcontainers.api.Version;

public class CheckVersion {
    public static void main(String[] args) {
        System.out.println("Altcontainers version: " + Version.version());
    }
}
```

If `Version.version()` returns `UNKNOWN`, the `version.properties` resource is missing from the
classpath; check that the `org.altcontainers:core` JAR (not a repackaged copy that omits
resources) is on the classpath.

## Learn next

- [Your First Container](first-container)
- [Project Setup](project-setup)
- [Configuration](../guides/configuration)
