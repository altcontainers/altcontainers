---
title: PrefixConsumer
description: A log consumer that formats Docker container output for stdout display.
---

# PrefixConsumer

`PrefixConsumer` implements `Consumer<String>` and prints formatted container log lines to `System.out`.

```java
package org.altcontainers.api;

public final class PrefixConsumer implements Consumer<String> {
    public static PrefixConsumer of(String prefix, String image);
    public void accept(String line);
}
```

## Usage

```java
ContainerSpec containerSpec = ContainerSpec.builder("nginx:1.27")
    .logConsumer(PrefixConsumer.of("NGINX", "nginx:1.27"))
    .build();
```

Output format:
```
[NGINX] nginx:1.27 | <log line>
```

- `prefix` — the label in brackets (e.g., `"NGINX"`)
- `image` — the Docker image name for identification

Null or blank lines are silently ignored.

## Custom consumers

Any `Consumer<String>` can be used:

```java
.logConsumer(line -> {
    if (line.contains("ERROR")) {
        System.err.println("ERROR: " + line);
    }
})
```

## Learn next

- [ContainerSpec](container-spec)
- [Guides: Log Consumers](../guides/log-consumers)
