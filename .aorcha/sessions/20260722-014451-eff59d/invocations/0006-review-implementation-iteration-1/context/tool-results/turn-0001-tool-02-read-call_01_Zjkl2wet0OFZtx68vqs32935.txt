# Implementation Report: Image Pull Timeout

## Summary

Implemented a configurable timeout for `ContainerManager.pullImage()` to prevent indefinitely blocking threads when the Docker daemon hangs during an image pull. Added a new `altcontainers.image.pull.timeout.ms` property (default 300000 ms = 5 minutes), applied it to both the `awaitCompletion()` call and the `CompletableFuture.get()` waiter path, and documented the new key in the website configuration guides.

## Files Changed

| File | Change | Rationale |
|---|---|---|
| `core/src/main/java/nonapi/org/altcontainers/api/AltcontainersProperties.java` | Added `IMAGE_PULL_TIMEOUT_MS` constant, `KeyDef` entry with default `"300000"`, and `imagePullTimeout()` accessor | Plugs the new timeout into the existing config resolution system |
| `core/src/main/resources/altcontainers.properties` | Added `altcontainers.image.pull.timeout.ms=300000` under new `# --- Image pull ---` section | Sets the classpath default (lowest-precedence layer) |
| `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java` | Modified `pullImage()`: declared `pullTimeoutMs` at method level before `while(true)`, replaced `awaitCompletion()` with timed variant, replaced `existing.get()` with `existing.get(timeout + 10s)`, added `TimeoutException` catch | Applies the timeout at both the pull and waiter levels |
| `core/src/test/java/nonapi/org/altcontainers/api/AltcontainersPropertiesTest.java` | Added 4 tests: default value, env override, invalid non-numeric, invalid non-positive. Updated `clearSystemPropertyKeys()` to clean up new key. | Validates property parsing and eager validation |
| `core/src/test/java/nonapi/org/altcontainers/api/ContainerManagerPullTest.java` | Added `shouldCompletePullWithinConfiguredTimeout` test with `@EnabledIf("dockerAvailable")` | Confirms timeout does not break normal pull behavior |
| `website/docs/guides/configuration.md` | Added Image section, env var naming table row, and complete config block entry | Documents the new property for users |
| `website/versioned_docs/version-0.3.0/guides/configuration.md` | Same changes as above (with `Programmatic` in Configuration Methods column) | Keeps versioned docs in sync |

## Test Results

- **AltcontainersPropertiesTest:** 28 tests, 0 failures, 0 errors (4 new tests pass)
- **Full `./mvnw clean verify`:** BUILD SUCCESS (all modules: Parent, Reaper, Core, Examples)
  - Total time: 6 min 56 sec
  - No test failures

## Deviations from Plan

None. All changes follow the approved plan exactly.

## Build Verification

```
Command: ./mvnw clean verify
Exit code: 0
Summary: All modules build and test successfully. Spotless formatting clean. JaCoCo reports generated. PMD analysis clean.
```
