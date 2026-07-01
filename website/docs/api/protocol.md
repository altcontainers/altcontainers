---
title: Protocol
description: HTTP protocol variant for Altcontainers container readiness probes.
---

# Protocol

`Protocol` is an enum representing the HTTP protocol variant used by `WaitCondition.HttpWait` readiness probes.

```java
package org.altcontainers.api;

public enum Protocol {
    HTTP,
    HTTPS;
}
```

## Values

| Value | Description |
|---|---|
| `HTTP` | Hypertext Transfer Protocol (unencrypted) |
| `HTTPS` | Hypertext Transfer Protocol Secure (encrypted) |

## Usage

`Protocol` is used indirectly through `ContainerSpec.Builder` wait methods. Users do not instantiate or configure `Protocol` directly — it is selected by which builder method is called:

```java
ContainerSpec.builder("my-service:latest")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")   // uses Protocol.HTTP
    .build();

ContainerSpec.builder("my-service:latest")
    .exposePorts(8443)
    .waitForHttpsResponse(8443, "/health")  // uses Protocol.HTTPS
    .build();
```

## Learn next

- [Wait Conditions](wait-conditions)
- [ContainerSpec](container-spec)
