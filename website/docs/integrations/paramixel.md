---
title: Paramixel Integration
description: How Paramixel uses Altcontainers for container lifecycle management in tests.
---

# Paramixel Integration

[Paramixel](https://www.paramixel.org) is a Java test orchestration framework that uses Altcontainers as its container management layer. Altcontainers was originally developed as the container lifecycle component of Paramixel.

## How Paramixel uses Altcontainers

Paramixel tests that need Docker containers create them through Altcontainers' `ContainerManager` and `ContainerSpec`. The Paramixel action tree manages the test structure (setup, execution, teardown, parallel branches), while Altcontainers manages the container lifecycle.

## Example: Nginx integration test

This example from the Paramixel examples shows an Nginx test that uses Altcontainers for container management:

```java
import org.altcontainers.api.Container;
import org.altcontainers.api.ContainerManager;
import org.altcontainers.api.ContainerSpec;
import org.altcontainers.api.Network;
import org.altcontainers.api.NetworkManager;
import org.altcontainers.api.PrefixConsumer;

public class NginxTestEnvironment implements AutoCloseable {
    private final String dockerImageName;

    public void initialize(Network network) {
        ContainerSpec spec = ContainerSpec.builder(dockerImageName)
            .command("nginx", "-g", "daemon off;")
            .exposePorts(80)
            .network(network, "nginx")
            .startupAttempts(3)
            .logConsumer(PrefixConsumer.of("NGINX", dockerImageName))
            .startupTimeout(Duration.ofSeconds(30))
            .waitForHttpResponse(80, "/")
            .build();
        container = ContainerManager.getInstance().createContainer(spec);
    }

    public void close() {
        if (container != null) {
            container.close();
        }
    }
}
```

## Using Altcontainers without Paramixel

Altcontainers is a standalone library with no dependency on Paramixel. You can use Altcontainers in any Java project, with any test framework (JUnit, TestNG, etc.), or in plain Java applications.

## Learn next

- [Maven](maven)
- [Gradle](gradle)
- [Altcontainers API Reference](../api/intro)
