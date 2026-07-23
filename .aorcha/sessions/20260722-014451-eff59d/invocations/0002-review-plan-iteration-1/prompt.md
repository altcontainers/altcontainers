Session: 20260722-014451-eff59d
Mode: execute
Project root: /home/dhoard/Development/github/altcontainers/altcontainers
Phase: REVIEW_PLAN
Iteration: 1




## Aorcha Workflow

Phases: PLAN → REVIEW_PLAN → IMPLEMENT → REVIEW_IMPLEMENTATION → VALIDATE.

When retrying, check Previous Artifacts for feedback that caused the retry.

## Tools

Available tools: read, grep, rg, find, ls, write — you can explore the repository and write review artifacts, but cannot edit source files

## Guidelines

- Read before you act. Use `read` and `grep` to understand relevant files.
- Make targeted edits with `edit`. Batch related changes in one call.
- Verify your work: implementers run build/tests; reviewers verify claims by reading code.
- Write artifacts to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts. Only files there are recognized as phase outputs.
- List every artifact in result.json's `artifacts` array.

Input plan:
# Fix: Image pull has no timeout — a hung daemon blocks all container creation indefinitely

**Severity:** Low
**Action:** `fix`
**Module:** `core`
**File:** `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java`

---

## Problem Statement

`ContainerManager.pullImage(String)` performs the pull with no timeout at two
levels:

1. The pulling thread: `dockerClient().pullImageCmd(image).start().awaitCompletion()`
   (line 204) — blocks until the daemon responds, potentially forever.
2. Waiting threads: `existing.get()` (line 223) — every concurrent caller for
   the same image blocks on the shared `CompletableFuture` with no timeout.

If the Docker daemon hangs mid-pull (network stall to registry, daemon
overload, socket issue with no read timeout on the zerodep transport), all
threads attempting to create containers from that image block indefinitely.
There is no way for a caller to bound this wait.

### Evidence

`core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java`,
`pullImage()`:

```java
dockerClient().pullImageCmd(image).start().awaitCompletion();   // no timeout
...
existing.get();                                                  // no timeout
```

Open question (verify before choosing the fix scope): whether
`ZerodepDockerHttpClient` applies any default socket read timeout that would
eventually unblock the pull. If it does, this degrades from a hang to a
diagnostic-quality issue.

## Proposed Fix

1. **Add a configurable pull timeout** following the existing
   `AltcontainersProperties` pattern:

   - New key `altcontainers.image.pull.timeout.ms`, default e.g. `600000`
     (10 minutes — pulls legitimately take minutes on slow links).
   - Add `KeyDef` entry in `AltcontainersProperties.KNOWN_KEYS`, an accessor
     `imagePullTimeout()`, and wire it into the public API. This keeps the
     documented precedence (programmatic > env > user-home > classpath > default).

2. **Apply the timeout in `pullImage`:**

```java
long timeoutMs = AltcontainersProperties.instance().imagePullTimeout().toMillis();
dockerClient().pullImageCmd(image).start().awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
```

   `awaitCompletion(long, TimeUnit)` returns `false` on timeout — treat
   `false` as failure:

```java
boolean completed = ...awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
if (!completed) {
    newFuture.completeExceptionally(new ContainerException("Image pull timed out: " + image));
    inflightPulls.remove(image);
    throw new ContainerException("Image pull timed out after " + timeoutMs + " ms: " + image);
}
```

3. **Bound the waiter path** with `existing.get(timeoutMs + slack,
   TimeUnit.MILLISECONDS)`; on `TimeoutException`, throw
   `ContainerException("Timed out waiting for in-flight image pull: " + image)`.
   Do not remove the in-flight future on waiter timeout (the pull may still
   succeed and benefit later callers).

## Naming Alignment

The proposed key `altcontainers.image.pull.timeout.ms` aligns with the existing
configuration property naming and environment variable derivation conventions:

**Property key pattern:** `altcontainers.<domain>.<subdomain>.<attribute>.<unit>`

Existing keys follow this pattern:

| Domain | Example Key |
|---|---|
| `reaper` | `altcontainers.reaper.connection.timeout.ms` |
| `container` | `altcontainers.container.startup.timeout.ms` |
| `wait` | `altcontainers.wait.http.probe.timeout.ms` |
| `networks` | `altcontainers.networks.parallelism` |
| `docker` | `altcontainers.docker.host` |

The new key introduces `image` as a domain, which is the correct granularity:

- **Image pulling is a distinct concern** from container lifecycle (`container.*`)
  and reaper cleanup (`reaper.*`). It occurs before any container exists.
- **Alternative placements considered and rejected:**
  - `altcontainers.container.image.pull.timeout.ms` — nests under `container`,
    but image pulling is not a container lifecycle operation.
  - `altcontainers.docker.image.pull.timeout.ms` — nests under `docker`, but
    `docker.*` currently only holds the host URI, not operational timeouts.
  - `altcontainers.pull.timeout.ms` — too generic; does not specify what is
    being pulled.

**Environment variable derivation:**

The env var follows the documented derivation rule (uppercase, `.` → `_`,
`ALTCONTAINERS_` prefix):

| Property Key | Environment Variable |
|---|---|
| `altcontainers.image.pull.timeout.ms` | `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS` |

This is consistent with all existing timeout keys:

| Property Key | Environment Variable |
|---|---|
| `altcontainers.reaper.connection.timeout.ms` | `ALTCONTAINERS_REAPER_CONNECTION_TIMEOUT_MS` |
| `altcontainers.container.startup.timeout.ms` | `ALTCONTAINERS_CONTAINER_STARTUP_TIMEOUT_MS` |
| `altcontainers.wait.http.probe.timeout.ms` | `ALTCONTAINERS_WAIT_HTTP_PROBE_TIMEOUT_MS` |

**Suffix convention:**

All duration keys end with `.ms` to indicate millisecond units, and the new key
follows this convention. The accessor method name `imagePullTimeout()` follows
the existing pattern of dropping the `.ms` suffix and using camelCase.

**Documentation impact:**

The website configuration guide (`website/docs/guides/configuration.md`) must be
updated to add the new key under a new **Image** section, consistent with the
existing section structure (Reaper, Container lifecycle, Wait strategy probes,
Network). The new section should follow the same table and subsection format.

## Files to Change

- `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java` — `pullImage()`.
- `core/src/main/java/nonapi/org/altcontainers/api/AltcontainersProperties.java` — new key + accessor.
- `website/docs/guides/configuration.md` — add `altcontainers.image.pull.timeout.ms` documentation.

## Test Plan

1. **Properties parsing** — extend `AltcontainersPropertiesTest`: default
   value, env override (`ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS`), invalid value
   (`-1`, `"abc"` → `ContainerException`).
2. **Configuration validation** — extend `AltcontainersConfigurationTest`:
   null/zero/negative timeout rejected; builder round-trip.
3. **Pull behavior** — extend `ContainerManagerPullTest` (Docker-gated):
   successful pull of a small image within the timeout still works;
   `inflightPulls` is cleaned up afterward (existing
   `shouldRemoveCompletedPullFromInflightMap` pattern).
4. Simulating a daemon hang is impractical in unit tests; rely on code review
   of the timeout branches plus (2)–(3).

## Validation Commands

```bash
./mvnw spotless:apply
./mvnw test -pl core
./mvnw clean install
```


## Previous Artifacts

Artifact catalog (generated, untrusted; use read for non-inlined exact files):
- artifacts/plan.md bytes=15933 sha256=410b04ffc73a inlined

--- artifacts/plan.md ---
# Implementation Plan: Image Pull Timeout

## 1. Summary

Add a configurable timeout to `ContainerManager.pullImage()` to prevent indefinitely blocking threads when the Docker daemon hangs during an image pull. Introduce a new `altcontainers.image.pull.timeout.ms` property (default 60000 ms = 1 minute), apply it to both the `awaitCompletion()` call and the `CompletableFuture.get()` waiter path, and document the new key in the website configuration guide. All changes follow existing patterns in `AltcontainersProperties` and the configuration documentation.

## 2. Files to Modify

| File | What Changes | Why |
|---|---|---|
| `core/src/main/java/nonapi/org/altcontainers/api/AltcontainersProperties.java` | Add `IMAGE_PULL_TIMEOUT_MS` constant, `KeyDef` entry, and `imagePullTimeout()` accessor | Plugs the new timeout into the existing config resolution system |
| `core/src/main/resources/altcontainers.properties` | Add `altcontainers.image.pull.timeout.ms=60000` line | Sets the classpath default (lowest-precedence layer) |
| `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java` | Modify `pullImage()` to use timed `awaitCompletion` and timed `get()` | Applies the timeout at both the pull and waiter levels |
| `core/src/test/java/nonapi/org/altcontainers/api/AltcontainersPropertiesTest.java` | Add tests for default value, env override, and invalid values | Validates property parsing and eager validation |
| `core/src/test/java/nonapi/org/altcontainers/api/ContainerManagerPullTest.java` | Add test verifying successful pull with timeout, verify inflightPulls cleanup | Confirms timeout does not break normal pull behavior |
| `website/docs/guides/configuration.md` | Add new **Image** section with key table and documentation subsection | Documents the new property for users |
| `website/versioned_docs/version-0.3.0/guides/configuration.md` | Same as above | Keep versioned docs in sync |

## 3. Step-by-Step Implementation

### Step 1: Add property key and accessor to `AltcontainersProperties.java`

**File:** `core/src/main/java/nonapi/org/altcontainers/api/AltcontainersProperties.java`

Add the following in order:

1. **Property key constant** (after `DOCKER_HOST` constant, ~line 110):

```java
/** Property key for {@link #imagePullTimeout()}. */
static final String IMAGE_PULL_TIMEOUT_MS = "altcontainers.image.pull.timeout.ms";
```

2. **KeyDef entry** in `KNOWN_KEYS` list (after the `DOCKER_HOST` entry, ~line 126):

```java
new KeyDef(IMAGE_PULL_TIMEOUT_MS, "60000", Kind.DURATION_MS),
```

3. **Accessor method** (after `dockerHost()` method, before `resolveRaw` private method, ~line 258):

```java
/**
 * Returns the image pull timeout.
 *
 * @return the image pull timeout
 */
public Duration imagePullTimeout() {
    return (Duration) resolved.get(IMAGE_PULL_TIMEOUT_MS);
}
```

**Expected outcome:** `./mvnw compile -pl core` passes. The new key is parsed with the same `DURATION_MS` validation (must be positive integer in ms). Invalid values (`abc`, `-1`, `0`) fail fast with `ContainerException`.

### Step 2: Add default to classpath properties file

**File:** `core/src/main/resources/altcontainers.properties`

Add after the wait strategy probes section (before `# --- Docker host ---`), following the existing comment style:

```properties
# --- Image pull ---
altcontainers.image.pull.timeout.ms=60000
```

**Expected outcome:** The default is visible in the bundled resource. Since classpath is the second-lowest precedence, it can be overridden by user-home, env, or system property.

### Step 3: Modify `pullImage()` in `ContainerManager.java`

**File:** `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java`

The `pullImage(String image)` method is at lines 195-236. Modify two locations:

**3a. Apply timeout to `awaitCompletion` (line 204):**

Replace:
```java
dockerClient().pullImageCmd(image).start().awaitCompletion();
```

With:
```java
long pullTimeoutMs = AltcontainersProperties.instance().imagePullTimeout().toMillis();
boolean completed =
        dockerClient().pullImageCmd(image).start().awaitCompletion(pullTimeoutMs, TimeUnit.MILLISECONDS);
if (!completed) {
    String message = "Image pull timed out after " + pullTimeoutMs + " ms: " + image;
    newFuture.completeExceptionally(new ContainerException(message));
    inflightPulls.remove(image);
    throw new ContainerException(message);
}
```

The `newFuture.completeExceptionally(...)` ensures all waiters blocked on `existing.get()` receive the timeout exception. The `inflightPulls.remove(image)` allows subsequent callers to retry.

**3b. Apply timeout to `existing.get()` (line 223):**

Replace:
```java
existing.get();
```

With:
```java
try {
    existing.get(pullTimeoutMs + 10_000, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    throw new ContainerException(
            "Timed out waiting for in-flight image pull: " + image, e);
}
```

The `+ 10_000` (10 second slack) accounts for the pulling thread's own timeout plus head-of-line scheduling variance. On `TimeoutException`, do NOT remove the in-flight future — the pull may still succeed and benefit later callers.

**3c. Add import for `TimeoutException` if not present:**

Check imports: `TimeoutException` is already imported at line 53. `TimeUnit` is already imported at line 54. No new imports needed.

**Expected outcome:** The `pullImage()` method now has bounded waits at both levels. All three existing exception handlers (`InterruptedException`, `RuntimeException`, `ExecutionException`) remain unchanged and continue to work.

### Step 4: Add tests to `AltcontainersPropertiesTest.java`

**File:** `core/src/test/java/nonapi/org/altcontainers/api/AltcontainersPropertiesTest.java`

Add four new test methods following existing patterns:

**4a. Default value test** (add as separate test):

```java
@Test
void shouldDefaultImagePullTimeout() {
    AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
    assertThat(p.imagePullTimeout()).isEqualTo(Duration.ofMinutes(1));
}
```

**4b. Environment variable override:**

```java
@Test
void shouldResolveImagePullTimeoutFromEnv() {
    Map<String, String> env = Map.of("ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS", "300000");
    AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), env);
    assertThat(p.imagePullTimeout()).isEqualTo(Duration.ofMinutes(5));
}
```

**4c. Invalid non-numeric value** (follow pattern of `shouldFailFastOnInvalidValueInUserHome`):

```java
@Test
void shouldFailFastOnInvalidImagePullTimeout() {
    Properties userHome = props(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS, "abc");
    assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
            .isInstanceOf(ContainerException.class)
            .hasMessageContaining(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS);
}
```

**4d. Invalid non-positive value** (follow pattern of `shouldFailFastOnNonPositiveValue`):

```java
@Test
void shouldFailFastOnNonPositiveImagePullTimeout() {
    Properties userHomeZero = props(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS, "0");
    assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHomeZero, Map.of()))
            .isInstanceOf(ContainerException.class)
            .hasMessageContaining(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS);

    Properties userHomeNeg = props(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS, "-1");
    assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHomeNeg, Map.of()))
            .isInstanceOf(ContainerException.class)
            .hasMessageContaining(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS);
}
```

**Note:** No existing test method needs to be modified. `shouldApplyDefaultsWhenNoSourceSet` checks all current properties but does not assert on an exhaustive list — the new key automatically gets its default and won't break existing assertions.

### Step 5: Add/update tests in `ContainerManagerPullTest.java`

**File:** `core/src/test/java/nonapi/org/altcontainers/api/ContainerManagerPullTest.java`

**5a. Verify existing test still works:** The `shouldRemoveCompletedPullFromInflightMap` test pulls `alpine:latest` via `triggerPullImage()` and checks that `inflightPulls` is cleaned up. This test now exercises the new timeout path implicitly — if the pull completes within the default 1-minute timeout (which it will on any reasonable connection), the behavior is identical. No changes needed.

**5b. Add a timeout configuration test** (optional, Docker-gated):

```java
@Test
@EnabledIf("dockerAvailable")
void shouldCompletePullWithinConfiguredTimeout() throws Exception {
    // Sanity check: alpine:latest pull completes well within 60 seconds
    String image = "alpine:latest";
    ContainerManager manager = ContainerManager.getInstance();

    long start = System.currentTimeMillis();
    manager.triggerPullImage(image);
    long elapsed = System.currentTimeMillis() - start;

    assertThat(elapsed).isLessThan(Duration.ofSeconds(50).toMillis());

    // Cleanup: verify inflightPulls is empty
    Field field = ContainerManager.class.getDeclaredField("inflightPulls");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, CompletableFuture<Void>> inflight =
            (ConcurrentHashMap<String, CompletableFuture<Void>>) field.get(manager);
    assertThat(inflight).doesNotContainKey(image);
}
```

**Expected outcome:** All existing pull tests pass. The new test confirms pulls complete within a reasonable time and inflight cleanup works.

### Step 6: Update website documentation

**File:** `website/docs/guides/configuration.md`

**6a. Add `altcontainers.image.pull.timeout.ms` to the "Environment variable naming" table** (after the Networks row):

```markdown
| `altcontainers.image.pull.timeout.ms` | `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS` |
```

**6b. Add new "Image" section** after "Container lifecycle" section and before "Wait strategy probes" section, following the same structure:

```markdown
---

### Image

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.image.pull.timeout.ms` | Duration (ms) | `60000` | Environment, Properties file | Maximum time to wait for an image pull to complete |

#### `altcontainers.image.pull.timeout.ms`

Maximum time the library waits for a Docker image pull to complete. If a pull exceeds this timeout, the pull is treated as failed and all threads waiting for the same image receive a `ContainerException`.

Concurrent callers that request the same image while a pull is in progress wait for the in-flight pull (rather than starting a duplicate pull). Their wait is bounded by this timeout plus a small slack (10 seconds).

**Values:** Positive integer (milliseconds)

**Properties file:**
```properties
altcontainers.image.pull.timeout.ms=300000
```

**Environment variable:**
```bash
ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS=300000
```
```

**6c. Add the new key to the "Complete default configuration" code block** (after the wait strategy probes):

```properties
# --- Image pull ---
altcontainers.image.pull.timeout.ms=60000
```

**File:** `website/versioned_docs/version-0.3.0/guides/configuration.md`

Apply the same changes as 6a-6c to keep the versioned docs consistent.

## 4. Test Plan

| Test | File | Proves |
|---|---|---|
| `shouldDefaultImagePullTimeout` | `AltcontainersPropertiesTest.java` (new) | Default value is 60000 ms (1 minute) |
| `shouldResolveImagePullTimeoutFromEnv` | `AltcontainersPropertiesTest.java` (new) | Env var `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS` overrides default |
| `shouldFailFastOnInvalidImagePullTimeout` | `AltcontainersPropertiesTest.java` (new) | Non-numeric value `"abc"` throws `ContainerException` |
| `shouldFailFastOnNonPositiveImagePullTimeout` | `AltcontainersPropertiesTest.java` (new) | Zero and negative values rejected eagerly |
| `shouldRemoveCompletedPullFromInflightMap` | `ContainerManagerPullTest.java` (existing) | Successful pull within timeout cleans up `inflightPulls` |
| `shouldPropagatePullFailureToAllWaiters` | `ContainerManagerPullTest.java` (existing) | Concurrent waiters receive failure from the shared future |
| `shouldCompletePullWithinConfiguredTimeout` | `ContainerManagerPullTest.java` (new, optional) | Alpine pull completes well within the 1-minute default |

**Note about `AltcontainersConfigurationTest`:** The input plan mentions extending `AltcontainersConfigurationTest` for configuration validation, but this test class does not exist in the repository. Configuration validation for the new key is handled entirely by the existing `DURATION_MS` kind in `AltcontainersProperties.parse()`, which validates `millis > 0` and rejects non-numeric values. The `AltcontainersPropertiesTest` additions in step 4 cover all validation scenarios.

**Note about simulating a daemon hang:** Not practical in unit tests. The timeout branches are verified through:
- Code review of the `pullImage()` modification (Steps 3a, 3b)
- The `DURATION_MS` validation tests (prevent zero/negative/invalid timeouts)
- Docker-gated tests confirming the happy path still works

## 5. Validation

Run the following commands in order:

```bash
# 1. Format code
./mvnw spotless:apply

# 2. Run core module tests (includes the new properties tests and Docker-gated pull tests)
./mvnw test -pl core

# 3. Full Maven validation (compiles all modules, runs all tests, static analysis, Javadoc)
./mvnw clean install
```

All three commands must pass with exit code 0.

## 6. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| **Default too short for slow links**: 1 minute may be insufficient on very slow connections or for large images | Configurable via all standard precedence layers. Users on slow links or pulling large images can set `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS=300000` (5 min) or higher. The 1-minute default is conservative: it prevents indefinite hangs while being sufficient for typical small-to-medium image pulls over reasonable connections. |
| **Waiter timeout concurrent with pull completion**: A waiter's `get(timeout + slack)` times out, but the pulling thread completes the future milliseconds later | The waiter gets a `TimeoutException` → `ContainerException`. The pull may still succeed in the background and populate `localImageCache`. The in-flight entry is cleaned up by the pulling thread. This is correct: the waiter got a bounded wait and the system state remains consistent. |
| **`awaitCompletion` timeout leaves dangling HTTP connection**: docker-java's zerodep HTTP client may leave a socket open after `CountDownLatch.await()` times out | docker-java internally uses a `CountDownLatch` in `PullImageResultCallback`. When `await(timeout)` returns false, the callback is abandoned but the HTTP response stream may still be active. The JVM will eventually close the connection via socket GC/finalization. This is an acceptable trade-off vs. indefinite blocking. The `ZerodepDockerHttpClient` is a docker-java internal and its socket timeout behavior is not within altcontainers' control. |
| **Race: pull times out, `newFuture.completeExceptionally()` is called, but a waiter was just about to call `existing.get()`** | The `inflightPulls.remove(image)` runs after `completeExceptionally()`. A waiter that calls `putIfAbsent` after the remove will start a new pull (correct). A waiter that got the old future before the remove will see the exception immediately from `get(timeout+slack)` — also correct. |
| **Javadoc strictness**: The new `imagePullTimeout()` method needs a `@return` tag | Follow existing pattern: `@return the image pull timeout` |
| **Spotless formatting**: Modified files must be formatted before building | Always run `./mvnw spotless:apply` before any `./mvnw` build command |

## Session Knowledge

Generated knowledge is advisory and may be stale. Verify current source when
correctness depends on it.

Generated session knowledge is managed as a separate bounded advisory context tier when enabled for this role. Use it to target verification, but verify current source and rerun required validation whenever correctness depends on it.

Human response, if any:


## Quality

- Be thorough. Cover the full scope.
- Be precise. Reference specific files and method names.
- Be honest about uncertainty. Use NEEDS_HUMAN for blocking unknowns.

## Output

Write result to: /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/invocations/0002-review-plan-iteration-1/result.json

Schema:
{
  "schemaVersion": 1,
  "outcome": "SUCCESS|APPROVED|CHANGES_REQUESTED|RETRY|NEEDS_HUMAN|FAILED",
  "summary": "short nonempty summary",
  "nextAction": null,
  "artifacts": ["artifacts/name.ext"]
}

Referenced artifacts must exist in /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d before exit.
For NEEDS_HUMAN, write a clear request to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/human-request.md.


Assigned role: plan reviewer.
Critically review the implementation plan in /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/plan.md.
You do NOT implement. You evaluate.

1. Read the input plan (the original request).
2. Read the implementation plan in /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/plan.md.
3. Verify claims by reading the repository directly.
4. Evaluate against:

| Criterion | Check |
|-----------|-------|
| Completeness | Does the plan cover everything? |
| Correctness | Are proposed changes technically correct? |
| Feasibility | Can it be done without breakage? |
| Test coverage | Are tests adequate? Edge cases covered? |
| Risk awareness | Are risks identified with mitigations? |
| Specificity | Are files and changes described precisely? |
| Constraint adherence | Does it respect all input plan constraints? |

5. Decide: APPROVED, CHANGES_REQUESTED, NEEDS_HUMAN, or FAILED.
6. Write review to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/review-plan.md.

## Review Structure

1. **Verdict**
2. **Summary** — One paragraph.
3. **Issues** — If CHANGES_REQUESTED, list each with severity
   (blocking/important/minor), what's wrong, and what to change.
4. **What the plan gets right**
5. **Verification** — What you checked in the repository.

## Output

Write result to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/invocations/0002-review-plan-iteration-1/result.json.
Allowed outcomes: APPROVED, CHANGES_REQUESTED, NEEDS_HUMAN, FAILED.
