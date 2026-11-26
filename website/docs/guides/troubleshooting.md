---
title: Troubleshooting
description: Common issues and solutions when using Altcontainers.
---

# Troubleshooting

## Container fails to start

### "Container did not become ready: ..."

The container started but the wait conditions were not satisfied within the readiness timeout. The
message lists each unsatisfied strategy's target, its last observed result, and the timeout, for
example:

```
Container did not become ready: HttpWaitStrategy failed for image nginx:1.27, container <id>, URL http://localhost:32768/, expected status 200..399, last status none within 60s; last error: ConnectException: Connection refused
```

**Causes:**
- The service inside the container is slower than expected
- The wrong port was exposed or waited on
- The HTTP endpoint returns an unexpected status code
- The log message regex doesn't match (matching is whole-string against newline-terminated lines)

**Solutions:**
- Increase `startupTimeout`. The timeout is used separately for the startup check and the
  readiness wait, so a full attempt can take up to roughly twice the configured value:
  ```java
  .startupTimeout(Duration.ofSeconds(120))
  ```
- Enable log output to see what the container is doing:
  ```java
  .outputListener(frame -> System.out.println("[APP] my-image | " + frame.safeUtf8StringWithoutLineEnding()))
  ```
- Verify the wait condition matches the service behavior. Use `.*pattern.*` for log substring
  matching.

### "failed startup check"

The container was created but exited before the startup check succeeded. The default
`StartupCheckStrategy.isRunning()` requires the container to still be running after Docker starts
it. The exception message includes the container id, image, and timeout.

**Causes:**
- The container command exits immediately (for example, a one-shot image such as `hello-world`)
- The container crashes during initialization
- A custom startup check rejects the container state

**Solutions:**
- Use a long-running command (for example, `alpine` with `sleep 30`)
- Enable log output to see why the process exited
- Provide a custom `startupCheckStrategy(...)` if "running" is not the right criterion

### Docker daemon unreachable

Container creation fails before the container exists because the Docker daemon cannot be reached.
The exception message, or its cause chain, names the connection problem — for example
`ConnectException: Connection refused`, or `No such file or directory` when the socket path does not
exist.

**Causes:**
- Docker daemon is not running
- The Docker host is misconfigured
- Permission denied on the Docker socket
- A Docker context or TLS configuration points at the wrong daemon

**Solutions:**
- Verify Docker is running: `docker info`
- Check the Docker host configuration. `altcontainers.docker.host` takes precedence when set
  (system property, `ALTCONTAINERS_DOCKER_HOST`, or properties file); otherwise docker-java honors
  `DOCKER_HOST`, `DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH`, `DOCKER_CONFIG`, and `DOCKER_CONTEXT`.
  See [Configuration](configuration).
- Ensure the current user has permission to access the Docker socket

### "Image pull failed"

**Causes:**
- Image name is incorrect or misspelled
- Docker registry is unreachable
- Authentication is required for a private registry
- The pull exceeded `altcontainers.image.pull.timeout.ms`

**Solutions:**
- Verify the image exists: `docker pull <image>`
- Check network connectivity
- For private registries, authenticate with the Docker CLI (`docker login <registry>`). docker-java
  reads credentials from the standard Docker configuration (`~/.docker/config.json` or
  `DOCKER_CONFIG`) when pulling. You can also pull the image beforehand with `docker pull <image>`
  so Altcontainers finds it locally.
- Increase `altcontainers.image.pull.timeout.ms` for slow registries. Pull failures happen before
  the startup retry loop, so `startupAttempts` does not retry them.

### "Reaper failed to start"

Container creation aborts before the container is created because the reaper process could not be
launched, or did not publish its port within `altcontainers.reaper.startup.timeout.ms`. A failed
reaper handshake is reported as `Reaper unavailable: ...` instead.

**Causes:**
- The reaper process could not be spawned (for example the `java` executable that started the
  application cannot be executed)
- The reaper JAR could not be extracted into the Java temp directory (`java.io.tmpdir`)

**Solutions:**
- Make sure the Java temp directory exists and is writable
- Inspect the reaper log `altcontainers-reaper-<sessionId>.log` in `java.io.tmpdir`, or in
  `altcontainers.reaper.log.directory` when that property is set
- Raise `altcontainers.reaper.startup.timeout.ms` on a slow or heavily loaded host
- Set `altcontainers.reaper.disabled=true` to run without the reaper. Every container and network must
  then be closed explicitly, because nothing cleans up resources that are still open when the JVM exits

## Container destroyed unexpectedly

A container that was running earlier is gone, or `isRunning()` returns `false`, while the test is
still using it.

**Causes:**
- The try-with-resources block ended before use
- The container process exited (crashed)
- The reaper cleaned it up

**Solutions:**
- Check that `Container` usage is inside the try-with-resources block
- Enable log output to see if the container process is crashing
- Avoid holding `Container` references outside their lifecycle scope
- Check `container.isRunning()` before using the container

## Port mapping issues

### `hostPort()` returns null

The container port is not in the cached bindings from the post-start inspection.

**Causes:**
- The port was not exposed via `.exposePorts()`
- Docker did not publish the port
- The spec used to create the container did not expose the port

**Solutions:**
- Always call `.exposePorts()` for ports you need to access
- Check for `null` before using the host port value
- Remember that `hostPort()` returns the mapping cached at startup; an external restart or
  re-creation is not reflected

## Log output appears as binary

### grep reports "binary file matches"

When container output is captured to a log file, GNU `grep` may classify the file as binary and suppress matches with the message `binary file matches`.

**Cause:** Container output frames may contain NUL bytes or unsafe control characters. When printed directly to a log file alongside test runner output, these bytes trigger binary classification.

**Solution:** Use `OutputFrame.safeUtf8StringWithoutLineEnding()` instead of `utf8StringWithoutLineEnding()` in your output consumer. The safe method removes NUL, unsafe C0/C1 control characters, DEL, and ANSI escape sequences while preserving printable text.

```java
// Before (may cause binary log classification)
.outputListener(frame -> System.out.println(frame.utf8StringWithoutLineEnding()))

// After (safe for text log output)
.outputListener(frame -> System.out.println(frame.safeUtf8StringWithoutLineEnding()))
```

For capture to a file, pipe safe output through your logging framework rather than printing raw frame text.

## Learn next

- [API: ContainerException](../api/container-exception)
- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
- [Configuration](configuration)
