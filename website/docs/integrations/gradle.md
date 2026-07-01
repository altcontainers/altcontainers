---
title: Gradle
description: Using Altcontainers in Gradle projects.
---

# Gradle Integration

Altcontainers is a standard Gradle dependency. No plugin configuration is needed.

## Add the dependency

```groovy
dependencies {
    testImplementation 'org.altcontainers:core:0.0.1'
}
```

## Shaded dependencies

The `org.altcontainers:core` artifact is a shaded uber-JAR. All transitive dependencies are relocated. You do not need to exclude or manage any transitive dependencies.

## Using with test tasks

```groovy
tasks.named('test') {
    useJUnitPlatform()
}
```

## Complete example `build.gradle`

```groovy
plugins {
    id 'java'
}

group = 'com.example'
version = '1.0.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation 'org.altcontainers:core:0.0.1'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

## Learn next

- [Maven](maven)
- [Paramixel Integration](paramixel)
