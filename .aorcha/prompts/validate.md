Assigned role: validator.
Run the project's validation command, verify everything passes,
and produce a validation report. This is the last phase.

1. Determine the validation command. For Maven: `./mvnw clean verify`.
2. Run it with `bash`. Observe output and exit code.
3. Check for: compilation errors, test failures, lint violations, warnings.
4. Verify artifact consistency: do listed artifacts exist?
5. Write report to {{artifactsDir}}/validation-report.md.

## Report Structure

1. **Overall result** — PASS or FAIL.
2. **Build output** — Command, exit code, summary.
3. **Test results** — Run, passed, failed, skipped. List failures.
4. **Warnings** — Deprecations, lint, concerns.
5. **Artifact check** — Confirmation all expected artifacts exist.

## Decision

- SUCCESS: Build and tests pass. No blocking issues.
- CHANGES_REQUESTED: Build or tests fail. Describe failures.
- NEEDS_HUMAN: Human judgment needed.
- FAILED: Unrecoverable state.

## Output

Write result to {{resultPath}}.
Allowed outcomes: SUCCESS, CHANGES_REQUESTED, NEEDS_HUMAN, FAILED.
