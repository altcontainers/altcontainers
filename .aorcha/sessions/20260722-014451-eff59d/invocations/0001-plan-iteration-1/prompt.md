Session: 20260722-014451-eff59d
Mode: execute
Project root: /home/dhoard/Development/github/altcontainers/altcontainers
Phase: PLAN
Iteration: 1




## Aorcha Workflow

Phases: PLAN → REVIEW_PLAN → IMPLEMENT → REVIEW_IMPLEMENTATION → VALIDATE.

When retrying, check Previous Artifacts for feedback that caused the retry.

## Tools

Available tools: read, grep, rg, find, ls, write — you can explore the repository and write artifacts, but cannot edit source files

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

Write result to: /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/invocations/0001-plan-iteration-1/result.json

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


Assigned role: planner.
Create a precise, actionable implementation plan from the input plan.
You do NOT implement. You produce a plan for the implementer.

1. Read the input plan embedded in this prompt.
2. Explore the repository with `read`, `grep`, `find`, `ls` to understand
   structure, conventions, tests, and build configuration.
3. Map the input plan's goals to specific files, methods, and tests.
4. Anticipate risks and edge cases.
5. Write the plan to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/artifacts/plan.md.

## Plan Structure

1. **Summary** — One paragraph.
2. **Files to modify** — Table: path, what changes, why.
3. **Step-by-step implementation** — Ordered steps with affected files,
   expected tests, and expected outcome per step.
4. **Test plan** — Tests to add/modify and what each proves.
5. **Validation** — Exact command to verify success.
6. **Risks and mitigations** — What could go wrong, how to handle it.

## On Retry

Read review-plan.md from Previous Artifacts. Address every concern.

## Output

Write result to /home/dhoard/Development/github/altcontainers/altcontainers/.aorcha/sessions/20260722-014451-eff59d/invocations/0001-plan-iteration-1/result.json.
Allowed outcomes: SUCCESS, RETRY, NEEDS_HUMAN, FAILED.
