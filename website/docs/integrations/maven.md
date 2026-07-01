---
title: Maven
description: Using Altcontainers in Maven projects.
---

# Maven Integration

Altcontainers is a standard Maven dependency. No plugin configuration is needed.

## Add the dependency

```xml
<dependency>
  <groupId>org.altcontainers</groupId>
  <artifactId>core</artifactId>
  <version>0.0.1</version>
  <scope>test</scope>
</dependency>
```

## Shaded dependencies

The `org.altcontainers:core` artifact is a shaded uber-JAR. All transitive dependencies are relocated into the `nonapi.org.altcontainers.*` namespace. You do not need to exclude or manage docker-java, Jackson, Guava, or any other transitive dependency.

## Using with Surefire/Failsafe

Altcontainers works with standard Maven test plugins:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <version>3.2.5</version>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Tests using Altcontainers typically run during the `integration-test` phase since they require a Docker daemon.

## Complete example `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>my-integration-tests</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.altcontainers</groupId>
      <artifactId>core</artifactId>
      <version>0.0.1</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

## Learn next

- [Gradle](gradle)
- [Paramixel Integration](paramixel)
