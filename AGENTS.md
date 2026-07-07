# Altcontainers Agent Instructions

Provider-neutral instructions for coding agents working in the Altcontainers repository. These rules apply regardless of LLM provider, model family, editor, or automation runtime.

---

## General Coding Principles

Reusable engineering discipline for coding agents. See
`.pi/prompts/coding-principles.md`.

---

## Altcontainers-Specific Rules

The following rules are specific to the Altcontainers repository and its build system.

## Critical Rules

- Before any Maven validation/build command (`test`, `check`, `package`, `install`, `verify`, `javadoc`, etc.), run `./mvnw spotless:apply`. The only exception is an explicitly requested read-only formatting check with `./mvnw spotless:check`.
- Preserve Java 17 compatibility and existing public API/exception semantics unless the user explicitly asks for a breaking change.
- Do not weaken Spotless, strict Javadoc, PMD, test, or build configuration to make validation pass.
- Prefer the smallest safe change and report the commands run with pass/fail results.

## Standard Agent Workflow

1. Inspect the relevant source, tests, build files, and repository guidance before changing files.
2. Make the smallest focused change that satisfies the request.
3. Run `./mvnw spotless:apply` before Maven validation.
4. Run the narrowest relevant validation command from the table below.
5. Prefer `./mvnw clean install` when touching shared/core behavior or build configuration.
6. Summarize changed files, validation commands, results, and any remaining risks.

For non-trivial features, refactors, or bugs requiring design, follow the
design-to-implementation workflow documented in `.pi/prompts/README.md`:
design interview → design plan → implementation spec → implement → verify.

## Validation Commands

Run `./mvnw spotless:apply` first for validation/build commands unless the task is a read-only formatting check.

| Task | Command |
| --- | --- |
| Format code | `./mvnw spotless:apply` |
| Full Maven validation with static analysis | `./mvnw clean install` |
| Core module JUnit tests only | `./mvnw test -pl core` |
| Examples tests (requires Docker) | `./mvnw test -pl examples` |
| Build without running any tests | `./mvnw clean install -DskipTests` |
| Check formatting only | `./mvnw spotless:check` |
| Check Javadoc only (Maven) | `./mvnw javadoc:javadoc` |
| Build Maven project | `./mvnw clean install` |

`-DskipTests` skips Surefire JUnit tests in standard test sources.

## Module Structure

- `core/` — Main library, deploys to Maven Central
- `examples/` — Examples using Altcontainers with Paramixel, not deployed

## Code Style and Java 17 Guardrails

- Spotless with Palantir Java Format runs automatically on `verify` phase.
- License header from `assets/license-header.txt` is required on all Java files.
- Run `./mvnw spotless:apply` before validation and before committing.
- Check formatting with `./mvnw spotless:check` when a read-only formatting check is needed.
- When modifying Java code, follow `.pi/prompts/java-code-review.md` and these guardrails:
  - Prefer clear, immutable post-build state over ad-hoc mutation.
  - Keep synchronization minimal and explicit.
  - Avoid novelty refactors; optimize only measurable hot paths.
  - Preserve null-safety contracts and existing exception semantics.
- For Java coverage improvement, see `.pi/prompts/java-code-coverage.md`.
- For Java performance analysis, see `.pi/prompts/java-performance-review.md`.

## Javadoc

- Strict Javadoc is enforced in the Maven build.
- Maven: `doclint:all` + `-Werror` on `maven-javadoc-plugin` (runs during `package` phase).
- Record compact constructors require their own `@param` tags, separate from record component `@param` tags.
- Missing `@param`, `@return`, or `@throws` tags will fail the build.
- The `examples/` module skips Javadoc generation entirely (`maven.javadoc.skip=true`).

## Planning

Plans go in `.pi/plans/`. See `.pi/prompts/planning-workflow.md` for naming conventions.

## Prompt Library

Reusable LLM-agnostic prompt templates live in `.pi/prompts/`. See
`.pi/prompts/README.md` for the full index and the design-to-implementation
workflow sequence.

## Static Analysis

PMD runs on `verify` phase:

- PMD remains report-only (`failOnViolation=false`).
- Custom rules: `assets/pmd-ruleset.xml`.
- CI skips on Java 25: `-Dpmd.skip=true`.

### Dead Code Detection

Dead code is identified through three complementary layers:

**Layer 1 — PMD rules (automated, build-time):** The PMD ruleset
detects these dead code categories during `./mvnw verify`:
`UnusedPrivateField`, `UnusedPrivateMethod`, `UnusedFormalParameter`,
`UnusedLocalVariable`, and `UnusedAssignment`. Violations appear in
the PMD report at `{module}/target/site/pmd.html`. PMD remains
report-only; violations do not fail the build but should be reviewed
and ideally removed.

**Layer 2 — JaCoCo coverage (automated, build-time):** JaCoCo
generates coverage reports at `{module}/target/site/jacoco/index.html`
during `verify`. Code with 0% line or branch coverage is a dead code
signal — it may be unreachable or have no test coverage. Coverage is a
signal, not a verdict; manually review 0%-coverage methods before
concluding they are dead.

**Layer 3 — IntelliJ IDEA inspections (manual, periodic):** Run
`Code > Inspect Code` on the project root and filter for "Unused
declaration" warnings. This catches dead code categories that
single-module PMD cannot: unused public methods, unused classes
visible within a module, and cross-module dead code. Review each
finding manually — some are false positives (public API methods,
Paramixel-invoked test methods via reflection).

## Commit Requirements

- DCO: All commits must be signed off with `git commit -s`.
- Conventional commit prefixes: `feature:`, `fix:`, `refactor:`, `chore:`, `performance:`, `polish:`.
- Dependency updates use scoped prefix: `chore(deps):` or `fix(deps):`.

## Release

Manual release with CI validation. See `RELEASING.md` for the release source of truth. Requires `~/.m2/settings.xml` (Maven Central credentials) and GPG signing key.

## Maven Version

Requires Maven 3.9+ (enforced by enforcer plugin).

## Java Compatibility

- Source/target/release: 17
- CI tests against Java 17, 21, 25
