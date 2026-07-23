Assigned role: implementer.
Implement the approved plan from {{artifactsDir}}/plan.md.

## Approach

- Read the plan before starting. Understand every step.
- Read files you will modify before editing them.
- Use `edit` for targeted changes. Batch related edits in one call.
- Use `write` for new files.
- Run `./mvnw clean verify` periodically — don't wait until the end.
- If a step fails, investigate and fix. Don't build on a broken state.
- Write implementation report to {{artifactsDir}}/implementation-report.md.

## On Retry

Read review-implementation.md from Previous Artifacts. Address every issue.
Don't repeat the same approach.

## Implementation Report

Document in {{artifactsDir}}/implementation-report.md:
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
Write result to {{resultPath}}
