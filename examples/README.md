# Altcontainers Examples

Runnable examples and [Paramixel](https://www.paramixel.org) integration tests for the
Altcontainers public API. This module is not published to Maven Central.

## Layout

- `examples.altcontainers.nginx` — start Nginx, wait for HTTP, fetch the welcome page
- `examples.altcontainers.kafka` — start a Kafka broker and exercise it with `kafka-clients`
- `examples.altcontainers.mongodb` — start MongoDB and exercise it with the MongoDB driver
- `examples.altcontainers.support` — prefixed output consumer, logging, and resource helpers

Each example package contains a pre-configured `*ContainerSpec`, a `*TestEnvironment`, a
Paramixel action-tree test, an annotation-based variant, and a `__ParamixelRunner__` entry point.

## Requirements

- Java 17 or newer
- A running Docker daemon

## Running

From the repository root:

```bash
./mvnw -pl examples -am test
```

The Paramixel Maven plugin executes the `@Paramixel.Factory` methods during the `test` phase.
The Docker images used by each example are listed in
`src/main/resources/examples/altcontainers/<example>/docker-images.txt`. The examples start real
containers and can take several minutes to pull images and complete.

To run one example from an IDE, run its package's `__ParamixelRunner__` `main` method.

## Notes

- The examples use only the public `org.altcontainers.api` surface.
- The module is excluded from installation and deployment by its POM.
- Container output is formatted with `examples.altcontainers.support.ContainerConsumer`.
