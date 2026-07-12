---
title: Protocol
description: HTTP protocol variant for Altcontainers container readiness probes.
---

# Protocol

`HttpWaitStrategy.Protocol` is an enum representing the HTTP protocol variant used by `HttpWaitStrategy` readiness probes.

```java
package org.altcontainers.api;

public enum HttpWaitStrategy.Protocol {
    HTTP,
    HTTPS_INSECURE,
    HTTPS_VERIFY;
}
```

## Values

| Value | Description |
|---|---|
| `HTTP` | Hypertext Transfer Protocol (unencrypted) |
| `HTTPS_INSECURE` | HTTPS with trust-all certificates, hostname verification disabled |
| `HTTPS_VERIFY` | HTTPS with strict certificate and hostname validation (JVM default) |

## Usage

`HttpWaitStrategy.Protocol` is used indirectly through `ContainerSpec.Builder` wait methods or directly with `HttpWaitStrategy.Builder`:

```java
// HTTP (default)
ContainerSpec.builder("my-service:latest")
    .exposePorts(8080)
    .waitForHttpResponse(8080, "/health")   // uses HttpWaitStrategy.Protocol.HTTP
    .build();

// HTTPS with trust-all certificates
ContainerSpec.builder("my-service:latest")
    .exposePorts(8443)
    .waitForHttpsResponse(8443, "/health")  // uses HttpWaitStrategy.Protocol.HTTPS_INSECURE
    .build();

// HTTPS with strict certificate validation
ContainerSpec.builder("my-service:latest")
    .exposePorts(8443)
    .waitStrategy(HttpWaitStrategy.builder()
        .protocol(HttpWaitStrategy.Protocol.HTTPS_VERIFY)
        .port(8443)
        .path("/health")
        .build())
    .build();
```

## Learn next

- [Wait Strategies](wait-strategies)
- [ContainerSpec](container-spec)
