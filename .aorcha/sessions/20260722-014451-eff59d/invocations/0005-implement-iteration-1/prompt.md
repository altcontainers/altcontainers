Session: 20260722-014451-eff59d
Mode: execute
Project root: /home/dhoard/Development/github/altcontainers/altcontainers
Phase: IMPLEMENT
Iteration: 1




## Aorcha Workflow

Phases: PLAN → REVIEW_PLAN → IMPLEMENT → REVIEW_IMPLEMENTATION → VALIDATE.

When retrying, check Previous Artifacts for feedback that caused the retry.

## Tools

Available tools: read, bash, edit, write, grep, rg, find, ls — you have full access to explore, edit, build, and test

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
- artifacts/plan.md bytes=22995 sha256=968db03074e5 inlined
- artifacts/review-plan.md bytes=4581 sha256=2fe412f62fa4 inlined

--- artifacts/plan.md ---
# Implementation Plan: Image Pull Timeout (v2)

## 1. Summary

Add a configurable timeout to `ContainerManager.pullImage()` to prevent indefinitely blocking threads when the Docker daemon hangs during an image pull. Introduce a new `altcontainers.image.pull.timeout.ms` property (default 300000 ms = 5 minutes), apply it to both the `awaitCompletion()` call and the `CompletableFuture.get()` waiter path, and document the new key in the website configuration guide. All changes follow existing patterns in `AltcontainersProperties` and the configuration documentation.

## 2. Files to Modify

| File | What Changes | Why |
|---|---|---|
| `core/src/main/java/nonapi/org/altcontainers/api/AltcontainersProperties.java` | Add `IMAGE_PULL_TIMEOUT_MS` constant, `KeyDef` entry with default `"300000"`, and `imagePullTimeout()` accessor | Plugs the new timeout into the existing config resolution system |
| `core/src/main/resources/altcontainers.properties` | Add `altcontainers.image.pull.timeout.ms=300000` line | Sets the classpath default (lowest-precedence layer) |
| `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java` | Modify `pullImage()` to use timed `awaitCompletion` and timed `get()`. Declare `pullTimeoutMs` at method level before `while(true)` to avoid scope error. | Applies the timeout at both the pull and waiter levels |
| `core/src/test/java/nonapi/org/altcontainers/api/AltcontainersPropertiesTest.java` | Add tests for default value (5 min), env override, and invalid values | Validates property parsing and eager validation |
| `core/src/test/java/nonapi/org/altcontainers/api/ContainerManagerPullTest.java` | Add test verifying successful pull with timeout, verify inflightPulls cleanup | Confirms timeout does not break normal pull behavior |
| `website/docs/guides/configuration.md` | Add new **Image** section with key table and documentation subsection | Documents the new property for users |
| `website/versioned_docs/version-0.3.0/guides/configuration.md` | Same as above (adapted for version-0.3.0 doc format with Programmatic sections) | Keep versioned docs in sync |

## 3. Step-by-Step Implementation

### Step 1: Add property key and accessor to `AltcontainersProperties.java`

**File:** `core/src/main/java/nonapi/org/altcontainers/api/AltcontainersProperties.java`

Add the following in order:

**1a. Property key constant** — insert after the `DOCKER_HOST` constant (the last existing constant before `KNOWN_KEYS`, ~line 119):

```java
/** Property key for {@link #imagePullTimeout()}. */
static final String IMAGE_PULL_TIMEOUT_MS = "altcontainers.image.pull.timeout.ms";
```

**1b. KeyDef entry** in `KNOWN_KEYS` list — insert after the `DOCKER_HOST` entry (current last element, ~line 134). Note: `DOCKER_HOST` is the last constant declared and the last entry in `KNOWN_KEYS`. The new entry goes after it:

```java
new KeyDef(IMAGE_PULL_TIMEOUT_MS, "300000", Kind.DURATION_MS),
```

**1c. Accessor method** — insert after the `dockerHost()` method and before the `resolveRaw` private method (~line 291). Follow the existing Javadoc pattern exactly:

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

Add after the wait strategy probes section (after `altcontainers.wait.http.probe.timeout.ms=2000` and its blank line, before the `# --- Docker host ---` comment block). Follow the existing comment style:

```properties
# --- Image pull ---
altcontainers.image.pull.timeout.ms=300000
```

**Expected outcome:** The default is visible in the bundled resource. Since classpath is the second-lowest precedence, it can be overridden by user-home, env, or system property.

### Step 3: Modify `pullImage()` in `ContainerManager.java`

**File:** `core/src/main/java/nonapi/org/altcontainers/api/ContainerManager.java`

The `pullImage(String image)` method spans lines 195-236. The key structural insight: the `if (existing == null)` block (puller path, lines 200-219) and the waiter path (lines 221-230, outside that `if` block) are siblings within the `while(true)` loop. **The `pullTimeoutMs` variable must be declared at method level (before `while(true)`) so it is accessible to both paths.** The previous iteration of this plan incorrectly scoped `pullTimeoutMs` inside the `if` block.

**CRITICAL: Declare `pullTimeoutMs` here, before the `while (true)` loop:**

```java
private void pullImage(String image) {
    long pullTimeoutMs = AltcontainersProperties.instance().imagePullTimeout().toMillis();
    while (true) {
```

**3a. Apply timeout to `awaitCompletion` (currently line 204):**

Replace:
```java
dockerClient().pullImageCmd(image).start().awaitCompletion();
```

With:
```java
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

**3b. Apply timeout to `existing.get()` (currently line 223):**

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

**IMPORTANT:** The `TimeoutException` catch must come *before* the existing `ExecutionException` catch, because `TimeoutException` is not a subclass of `ExecutionException` and the catch order doesn't matter for these two — but keep the new catch adjacent to the `get()` call for clarity. The existing `ExecutionException` and `InterruptedException` catch blocks remain unchanged and after the new `TimeoutException` catch.

The `+ 10_000` (10 second slack) accounts for the pulling thread's own timeout plus head-of-line scheduling variance. On `TimeoutException`, do NOT remove the in-flight future — the pull may still succeed and benefit later callers.

**3c. Imports:** `TimeoutException` is already imported at line 55. `TimeUnit` is already imported at line 54. No new imports needed.

**Complete modified method for reference:**

```java
private void pullImage(String image) {
    long pullTimeoutMs = AltcontainersProperties.instance().imagePullTimeout().toMillis();
    while (true) {
        CompletableFuture<Void> newFuture = new CompletableFuture<>();
        CompletableFuture<Void> existing = inflightPulls.putIfAbsent(image, newFuture);
        if (existing == null) {
            try {
                boolean completed = dockerClient()
                        .pullImageCmd(image)
                        .start()
                        .awaitCompletion(pullTimeoutMs, TimeUnit.MILLISECONDS);
                if (!completed) {
                    String message = "Image pull timed out after " + pullTimeoutMs + " ms: " + image;
                    newFuture.completeExceptionally(new ContainerException(message));
                    inflightPulls.remove(image);
                    throw new ContainerException(message);
                }
                newFuture.complete(null);
                localImageCache.add(image);
                inflightPulls.remove(image, newFuture);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                newFuture.completeExceptionally(e);
                inflightPulls.remove(image);
                throw new ContainerException("Image pull interrupted: " + image, e);
            } catch (RuntimeException e) {
                newFuture.completeExceptionally(e);
                inflightPulls.remove(image);
                throw e instanceof ContainerException ce
                        ? ce
                        : new ContainerException("Image pull failed: " + image, e);
            }
        }
        try {
            existing.get(pullTimeoutMs + 10_000, TimeUnit.MILLISECONDS);
            return;
        } catch (TimeoutException e) {
            throw new ContainerException(
                    "Timed out waiting for in-flight image pull: " + image, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ContainerException ce) {
                throw ce;
            }
            throw new ContainerException("Image pull failed: " + image, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerException("Interrupted while waiting for image pull", e);
        }
    }
}
```

**Expected outcome:** The `pullImage()` method now has bounded waits at both levels, and `pullTimeoutMs` is correctly scoped. All three existing exception handlers (`InterruptedException`, `RuntimeException`, `ExecutionException`) remain unchanged and continue to work.

### Step 4: Add tests to `AltcontainersPropertiesTest.java`

**File:** `core/src/test/java/nonapi/org/altcontainers/api/AltcontainersPropertiesTest.java`

Add four new test methods following existing patterns. Place them after `shouldResolveDockerHostFromSystemProperty` (the last existing test, ~line 225) and before the `props()` helper method. Also update `clearSystemPropertyKeys()` to clean up the new key.

**4a. Default value test:**

```java
@Test
void shouldDefaultImagePullTimeout() {
    AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), Map.of());
    assertThat(p.imagePullTimeout()).isEqualTo(Duration.ofMinutes(5));
}
```

**4b. Environment variable override:**

```java
@Test
void shouldResolveImagePullTimeoutFromEnv() {
    Map<String, String> env = Map.of("ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS", "120000");
    AltcontainersProperties p = AltcontainersProperties.forTesting(new Properties(), new Properties(), env);
    assertThat(p.imagePullTimeout()).isEqualTo(Duration.ofMinutes(2));
}
```

**4c. Invalid non-numeric value** (follows pattern of `shouldFailFastOnInvalidValueInUserHome`):

```java
@Test
void shouldFailFastOnInvalidImagePullTimeout() {
    Properties userHome = props(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS, "abc");
    assertThatThrownBy(() -> AltcontainersProperties.forTesting(new Properties(), userHome, Map.of()))
            .isInstanceOf(ContainerException.class)
            .hasMessageContaining(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS);
}
```

**4d. Invalid non-positive value** (follows pattern of `shouldFailFastOnNonPositiveValue`):

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

**4e. Update `clearSystemPropertyKeys()`:** Add cleanup for the new key:

```java
System.clearProperty(AltcontainersProperties.IMAGE_PULL_TIMEOUT_MS);
```

**Note:** No existing test method needs to be modified. `shouldApplyDefaultsWhenNoSourceSet` checks all current properties but does not assert on an exhaustive list — the new key automatically gets its default and won't break existing assertions.

### Step 5: Add tests to `ContainerManagerPullTest.java`

**File:** `core/src/test/java/nonapi/org/altcontainers/api/ContainerManagerPullTest.java`

**5a. Verify existing tests still work:** The `shouldRemoveCompletedPullFromInflightMap` test pulls `alpine:latest` via `triggerPullImage()` and checks that `inflightPulls` is cleaned up. This test now exercises the new timeout path implicitly — if the pull completes within the default 5-minute timeout (which it will on any reasonable connection), the behavior is identical. No changes needed to existing tests.

**5b. Add a timeout-configuration sanity test** (Docker-gated):

```java
@Test
@EnabledIf("dockerAvailable")
void shouldCompletePullWithinConfiguredTimeout() throws Exception {
    // Sanity check: alpine:latest pull completes well within 5-minute default
    String image = "alpine:latest";
    ContainerManager manager = ContainerManager.getInstance();

    long start = System.currentTimeMillis();
    manager.triggerPullImage(image);
    long elapsed = System.currentTimeMillis() - start;

    assertThat(elapsed).isLessThan(Duration.ofMinutes(4).toMillis());

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

Two documentation files need identical updates (adjusted for each version's format).

**File: `website/docs/guides/configuration.md`** (latest version)

**6a. Add to "Environment variable naming" table** — after the Networks row (`| altcontainers.networks.parallelism | ...`):

```markdown
| `altcontainers.image.pull.timeout.ms` | `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS` |
```

**6b. Add new "Image" section** — after the "Container lifecycle" section (after the `altcontainers.container.put.archive.pipe.buffer.bytes` subsection) and before the "Wait strategy probes" section:

```markdown
---

### Image

| Property Key | Type | Default | Configuration Methods | Purpose |
|---|---|---|---|---|
| `altcontainers.image.pull.timeout.ms` | Duration (ms) | `300000` | Environment, Properties file | Maximum time to wait for an image pull to complete |

#### `altcontainers.image.pull.timeout.ms`

Maximum time the library waits for a Docker image pull to complete. If a pull exceeds this timeout, the pull is treated as failed and all threads waiting for the same image receive a `ContainerException`.

Concurrent callers that request the same image while a pull is in progress wait for the in-flight pull (rather than starting a duplicate pull). Their wait is bounded by this timeout plus a small slack (10 seconds).

**Values:** Positive integer (milliseconds)

**Properties file:**
```properties
altcontainers.image.pull.timeout.ms=600000
```

**Environment variable:**
```bash
ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS=600000
```
```

**6c. Add to "Complete default configuration" code block** — after the wait strategy probes lines and a blank line, before the closing ` ``` `:

```properties
# --- Image pull ---
altcontainers.image.pull.timeout.ms=300000
```

**File: `website/versioned_docs/version-0.3.0/guides/configuration.md`**

Apply the same changes as 6a-6c, with these adaptations for the version-0.3.0 format:

- **6a:** Identical — the env var naming table is the same.
- **6b:** The "Configuration Methods" column in the table should say `Programmatic, Environment, Properties file`. Add "Programmatic:" code block before "Properties file:" in each key subsection. The "Programmatic" code block for `imagePullTimeout` would reference `Altcontainers.configure(builder -> builder.imagePullTimeout(Duration.ofMinutes(10)))` — but since `Altcontainers.configure()` and the builder API don't exist yet in the actual codebase, **omit the Programmatic code block** from both doc versions to keep docs aligned with current reality. The version-0.3.0 doc already has Programmatic sections for other keys, but those are aspirational. For the new Image section, use the same format as the latest docs (Environment, Properties file only) to avoid documenting a feature that doesn't exist.
- **6c:** Identical content.

## 4. Test Plan

| Test | File | Proves |
|---|---|---|
| `shouldDefaultImagePullTimeout` | `AltcontainersPropertiesTest.java` (new) | Default value is 300000 ms (5 minutes) |
| `shouldResolveImagePullTimeoutFromEnv` | `AltcontainersPropertiesTest.java` (new) | Env var `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS` overrides default |
| `shouldFailFastOnInvalidImagePullTimeout` | `AltcontainersPropertiesTest.java` (new) | Non-numeric value `"abc"` throws `ContainerException` |
| `shouldFailFastOnNonPositiveImagePullTimeout` | `AltcontainersPropertiesTest.java` (new) | Zero and negative values rejected eagerly |
| `shouldRemoveCompletedPullFromInflightMap` | `ContainerManagerPullTest.java` (existing) | Successful pull within timeout cleans up `inflightPulls` |
| `shouldPropagatePullFailureToAllWaiters` | `ContainerManagerPullTest.java` (existing) | Concurrent waiters receive failure from the shared future |
| `shouldCompletePullWithinConfiguredTimeout` | `ContainerManagerPullTest.java` (new, optional) | Alpine pull completes well within the 5-minute default |

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
| **Default too short for slow links**: 5 minutes may be insufficient for very large images or extremely slow connections | Configurable via all standard precedence layers. Users can set `ALTCONTAINERS_IMAGE_PULL_TIMEOUT_MS=600000` (10 min) or higher. The 5-minute default balances user protection against indefinite hangs with the reality that most image pulls complete within 1-2 minutes. |
| **`pullTimeoutMs` scope error**: Declaring inside the `if` block makes it inaccessible to the waiter path | **FIXED in this plan:** Declare `pullTimeoutMs` at method level before `while(true)`. Both Step 3a (puller) and Step 3b (waiter) reference the same variable. |
| **Waiter timeout concurrent with pull completion**: A waiter's `get(timeout + slack)` times out, but the pulling thread completes the future milliseconds later | The waiter gets a `TimeoutException` → `ContainerException`. The pull may still succeed in the background and populate `localImageCache`. The in-flight entry is cleaned up by the pulling thread. This is correct: the waiter got a bounded wait and the system state remains consistent. |
| **`awaitCompletion` timeout leaves dangling HTTP connection**: docker-java's zerodep HTTP client may leave a socket open after `CountDownLatch.await()` times out | docker-java internally uses a `CountDownLatch` in `PullImageResultCallback`. When `await(timeout)` returns false, the callback is abandoned but the HTTP response stream may still be active. The JVM will eventually close the connection via socket GC/finalization. This is an acceptable trade-off vs. indefinite blocking. The `ZerodepDockerHttpClient` is a docker-java internal and its socket timeout behavior is not within altcontainers' control. |
| **Race: pull times out, `newFuture.completeExceptionally()` is called, but a waiter was just about to call `existing.get()`** | The `inflightPulls.remove(image)` runs after `completeExceptionally()`. A waiter that calls `putIfAbsent` after the remove will start a new pull (correct). A waiter that got the old future before the remove will see the exception immediately from `get(timeout+slack)` — also correct. |
| **Javadoc strictness**: The new `imagePullTimeout()` method needs a `@return` tag | Follow existing pattern: `@return the image pull timeout` |
| **Spotless formatting**: Modified files must be formatted before building | Always run `./mvnw spotless:apply` before any `./mvnw` build command |
| **Breaking `shouldApplyDefaultsWhenNoSourceSet`**: Adding a new key changes the resolved map size | The existing test does not assert on map size — it asserts individual accessor values. A new key with a default does not affect any existing assertion. |

## 7. Changes from Previous Iteration (Iteration 1)

This v2 plan addresses all three issues identified in the review of iteration 1:

| Issue | Fix |
|---|---|
| **Issue 1 (blocking):** `pullTimeoutMs` declared inside `if (existing == null)` block but used in waiter path outside that block | Declare `pullTimeoutMs` at method level before `while(true)`. Step 3 now shows the declaration at the correct scope, and the complete method reference code is corrected. |
| **Issue 2 (important):** Default value 60000 ms (1 minute) too short | Changed default to 300000 ms (5 minutes) in: `KeyDef` default, `altcontainers.properties`, `shouldDefaultImagePullTimeout` assertion, website docs Default column. Changed `shouldCompletePullWithinConfiguredTimeout` assertion from `isLessThan(50s)` to `isLessThan(4 min)`. Updated `shouldResolveImagePullTimeoutFromEnv` to use `120000` (2 min) for a distinct test value. |
| **Issue 3 (minor):** Constant placement not explicitly documented | Step 1 now explicitly states: insert after `DOCKER_HOST` constant (last constant before `KNOWN_KEYS`). |


--- artifacts/review-plan.md ---
# Review: Image Pull Timeout Implementation Plan (v2, Iteration 2)

## Verdict: APPROVED

---

## Summary

The v2 plan resolves all three issues identified in the previous review (iteration 1). The `pullTimeoutMs` variable is now correctly declared at method level before `while(true)`, making it accessible to both the puller and waiter paths. The default value has been changed from 60,000 ms to 300,000 ms (5 minutes) across all locations. The constant placement is explicitly documented. All code snippets match the verified repository contents, the docker-java API (`awaitCompletion(long, TimeUnit)`) exists with the correct return type (`boolean`), and all test patterns align with existing conventions. No new issues were found.

---

## Issues

**No blocking or important issues found.** The plan is ready for implementation.

---

## What the plan gets right

1. **All three previous issues resolved correctly:**
   - **Scope error (blocking):** `pullTimeoutMs` now declared at method level in Step 3 (`long pullTimeoutMs = ...` before `while(true)`). The complete method reference code is correct.
   - **Default value (important):** All references use `300000` / `Duration.ofMinutes(5)` consistently — `KeyDef` default, `altcontainers.properties`, test assertions, and website documentation.
   - **Constant placement (minor):** Step 1a and 1b explicitly say "after the `DOCKER_HOST` constant" and "after the `DOCKER_HOST` entry", which matches the verified source file.

2. **Pattern consistency:** Correctly follows all existing conventions:
   - `KeyDef` with `Kind.DURATION_MS` (matches all existing duration keys)
   - Accessor method Javadoc pattern (`@return the image pull timeout`)
   - `forTesting` factory with `Properties classpath, Properties userHome, Map<String, String> env`
   - Test naming (`shouldDefaultImagePullTimeout`, `shouldResolveImagePullTimeoutFromEnv`, etc.)

3. **API verification:** `awaitCompletion(long, TimeUnit)` — confirmed via `javap` on `ResultCallbackTemplate.class` (docker-java-api 3.7.1). Returns `boolean`, which matches the plan's `boolean completed = ...awaitCompletion(...)` usage.

4. **Import verification:** Verified `TimeoutException` (line 53-54) and `TimeUnit` (line 52-53) are already imported in `ContainerManager.java`. No new imports needed.

5. **Test coverage:** Four property tests (default, env override, invalid non-numeric, invalid non-positive) plus a Docker-gated sanity test. The `clearSystemPropertyKeys()` update is correctly included.

6. **Waiter path design:** Correctly does NOT remove the in-flight future on waiter `TimeoutException` — the pull may still succeed for later callers.

7. **Risk identification:** Covers all edge cases (concurrent timeout vs. completion, dangling HTTP connections, race conditions, Javadoc strictness, Spotless formatting). Honest about each risk.

8. **Documentation accuracy:** Both doc files verified. The unversioned (`website/docs/guides/configuration.md`) uses "Environment, Properties file" (no Programmatic references) — consistent with current codebase. The version-0.3.0 doc already has Programmatic sections for existing keys but the plan correctly omits Programmatic from the new Image section since the builder API doesn't exist.

---

## Verification summary

| Check | Result | Source |
|---|---|---|
| `pullTimeoutMs` scope fix | ✅ Correct (method-level) | plan.md Step 3 |
| Default value 300000 (5 min) | ✅ Consistent across all locations | plan.md Steps 1b, 2, 4a, 6b, 6c |
| `awaitCompletion(long, TimeUnit)` return type | ✅ `boolean` | `javap ResultCallbackTemplate.class` |
| `TimeoutException` import | ✅ Present at line 53-54 | `ContainerManager.java` |
| `TimeUnit` import | ✅ Present at line 52-53 | `ContainerManager.java` |
| `triggerPullImage` visibility | ✅ Package-private | `ContainerManager.java` line 143 |
| Constant placement (after DOCKER_HOST) | ✅ Explicitly stated | plan.md Step 1a, 1b; `AltcontainersProperties.java` line 119 |
| Unversioned docs — no Programmatic refs | ✅ Correct | `website/docs/guides/configuration.md` |
| Version-0.3.0 docs — Image section format | ✅ Correct (Programmatic omitted) | plan.md Step 6 note |
| Test `clearSystemPropertyKeys` update | ✅ Included (adds `IMAGE_PULL_TIMEOUT_MS`) | plan.md Step 4e |
| Test `shouldApplyDefaultsWhenNoSourceSet` impact | ✅ None (non-exhaustive assertions) | `AltcontainersPropertiesTest.java` |
| `AltcontainersConfigurationTest` existence | ✅ Does not exist — plan correctly notes this | `find` verification from iter 1 |

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

Write result to: /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/invocations/0005-implement-iteration-1/result.json

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


Assigned role: implementer.
Implement the approved plan from /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/plan.md.

## Approach

- Read the plan before starting. Understand every step.
- Read files you will modify before editing them.
- Use `edit` for targeted changes. Batch related edits in one call.
- Use `write` for new files.
- Run `./mvnw clean verify` periodically — don't wait until the end.
- If a step fails, investigate and fix. Don't build on a broken state.
- Write implementation report to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/implementation-report.md.

## On Retry

Read review-implementation.md from Previous Artifacts. Address every issue.
Don't repeat the same approach.

## Implementation Report

Document in /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/implementation-report.md:
- Files changed and why
- Test results (pass/fail/issues)
- Deviations from the plan and rationale
- Build verification (command, exit code, summary)

## Before SUCCESS

- [ ] All planned changes are implemented.
- [ ] `./mvnw clean verify` passes (exit code 0, no test failures).
- [ ] No unintended files modified (`git diff --stat`).
- [ ] Implementation report is written.

## Contingencies

- RETRY: transient issue, try again
- NEEDS_HUMAN: blocking question only a human can answer
- FAILED: the plan is impossible to implement

## Output

Allowed outcomes: SUCCESS, RETRY, NEEDS_HUMAN, FAILED.
Write result to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/invocations/0005-implement-iteration-1/result.json
