---
title: Troubleshooting
description: Common issues and solutions when using Altcontainers.
---

# Troubleshooting

## Container fails to start

### "Container not ready after \<timeout\>"

The container started but the wait conditions were not satisfied within the `startupTimeout`.

**Causes:**
- The service inside the container is slower than expected
- The wrong port was exposed or waited on
- The HTTP endpoint returns an unexpected status code
- The log message regex doesn't match

**Solutions:**
- Increase `startupTimeout`:
  ```java
  .startupTimeout(Duration.ofSeconds(120))
  ```
- Enable log output to see what the container is doing:
  ```java
  .logConsumer(line -> System.out.println("[APP] my-image | " + line))
  ```
- Verify the wait condition matches the service behavior

### "Cannot connect to Docker daemon"

**Causes:**
- Docker daemon is not running
- `DOCKER_HOST` or `altcontainers.docker.host` is misconfigured
- Permission denied on the Docker socket

**Solutions:**
- Verify Docker is running: `docker info`
- Check `DOCKER_HOST` environment variable or `altcontainers.docker.host` system property
- Ensure the current user has permission to access the Docker socket

### "Image pull failed"

**Causes:**
- Image name is incorrect or misspelled
- Docker registry is unreachable
- Authentication required for private registry

**Solutions:**
- Verify the image exists: `docker pull <image>`
- Check network connectivity
- For private registries, authenticate with `docker login` first

## Container destroyed unexpectedly

### "Container no longer running"

The container was destroyed between creation and use.

**Causes:**
- The try-with-resources block ended before use
- The container process exited (crashed)
- The reaper cleaned it up

**Solutions:**
- Check that `Container` usage is inside the try-with-resources block
- Enable log output to see if the container process is crashing
- Avoid holding `Container` references outside their lifecycle scope

## Port mapping issues

### "hostPort returns -1"

The container port was not found in the port mapping.

**Causes:**
- The port was not exposed via `.exposePorts()`
- The container has stopped
- Docker did not publish the port

**Solutions:**
- Always call `.exposePorts()` for ports you need to access
- Check `container.isRunning()` before calling `hostPort()`

## Learn next

- [API: ContainerException](../api/container-exception)
- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
