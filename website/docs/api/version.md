---
title: Version
description: Accessing the Altcontainers library version at runtime.
---

# Version

`Version` provides the Altcontainers library version string.

```java
package org.altcontainers.api;

public final class Version {
    public static final String UNKNOWN = "UNKNOWN";
    public static String version();
}
```

## Usage

```java
System.out.println("Altcontainers version: " + Version.version());
```

Returns:
- The version string from the classpath resource `containers-version.properties` (property `altcontainers.core.version`)
- `"UNKNOWN"` if the resource is missing or unreadable

## Learn next

- [API Overview](intro)
- [Javadoc](javadocs)
