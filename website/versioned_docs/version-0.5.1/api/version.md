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
- The version string from the `version.properties` resource bundled in the `org/altcontainers/api` package of the Altcontainers JAR (property `version`)
- `"UNKNOWN"` if the resource is missing or unreadable

## Learn next

- [API Overview](intro)
- [Javadoc](javadocs)
