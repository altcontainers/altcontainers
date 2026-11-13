# Releasing Altcontainers

This repository publishes artifacts to Maven Central through a manual release process with CI validation.

## Prerequisites

### Maven Central Credentials

A Maven `settings.xml` file at `~/.m2/settings.xml` with your Sonatype Central credentials:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_CENTRAL_USERNAME</username>
      <password>YOUR_CENTRAL_TOKEN</password>
    </server>
  </servers>
</settings>
```

### GPG Signing Key

Maven Central requires all published artifacts to be signed and the signing key published to public key servers.

```bash
# Generate a dedicated GPG key for releases (if you don't have one)
gpg --full-generate-key
# RSA 4096, 2-year expiry recommended

# List your key to get the Key ID
gpg --list-secret-keys --keyid-format=long
# Look for: sec   rsa4096/XXXXXXXXXXXXXXXX

# Publish the public key to key servers (required for Maven Central)
gpg --keyserver keyserver.ubuntu.com --send-keys XXXXXXXXXXXXXXXX
gpg --keyserver keys.openpgp.org --send-keys XXXXXXXXXXXXXXXX
gpg --keyserver pgp.mit.edu --send-keys XXXXXXXXXXXXXXXX

# Verify signing works
echo "test" | gpg --batch --clearsign >/dev/null
```

### Documentation Publishing

Documentation is published independently of Maven releases; see [Publishing Documentation](#publishing-documentation).
The `WWW_ALTCONTAINERS_ORG` environment variable must be set to your SSH host alias for documentation publishing.

## Automated Release

The `./scripts/publish-release.sh` script automates the entire release process except for publishing from the Maven Central Portal, which requires manual verification. It publishes Maven artifacts only; the documentation site is published separately with `./scripts/publish-documentation.sh`.

The script records the next step and release commit in the Git common directory at
`release-state/<version>`. Rerun the same command to resume a partial release;
completed releases are no-ops. These checkpoints are local and do not travel with
a clone. Existing releases without checkpoints require manual recovery.

Dry runs are offline: they read local checkpoint metadata and print the workflow
without running Maven, fetching Git refs, checking credentials, or writing state.
Execution refreshes origin and validates the prerequisites for the remaining steps.

### Usage

```bash
# Dry run (default) — prints all commands without executing
./scripts/publish-release.sh 1.2.3

# Execute the release
./scripts/publish-release.sh 1.2.3 --execute
```

### Options

| Option | Description |
| ------ | ----------- |
| `--execute` | Execute the release (default is dry-run) |
| `--retry-deploy` | Retry step 3 from a step-4 checkpoint after confirming the version is unpublished and all pending deployments have been dropped |
| `-h`, `--help` | Show help text |

### What the script does

| Step | Action | Automated |
| ---- | ------ | --------- |
| Pre-flight | Validates version format, clean tree, branch sync, executables, GPG, settings.xml | Yes |
| 1 | Create release branch, set version, build, push | Yes |
| 2 | Wait for CI to pass | Pause for user confirmation |
| 3 | Deploy artifacts to Sonatype Central | Yes |
| 4 | Verify and publish at Maven Central Portal | **Manual** — user publishes from the portal |
| 5 | Tag and push tag | Yes (after Step 4 confirmed) |
| 6 | Bump main to development version (`<VERSION>-POST`) | Yes |

The script prompts before each required push and verifies existing tags against the
release commit. Answering **N** at Step 4 stops and preserves the branches and
checkpoint. It does not delete branches, tags, or Central deployments.

### Steps the script performs

#### Step 1 — Prepare the release branch

From `main`, creates the release branch, sets the version, validates the build, then pushes:

```bash
git checkout -b release/<VERSION>
./mvnw versions:set-property -Dproperty=revision -DnewVersion=<VERSION> -DgenerateBackupPoms=false
./mvnw spotless:apply
./mvnw clean install
git add -A
git commit -s -m "chore: Release <VERSION>"
git push -u origin release/<VERSION>
```

#### Step 2 — Wait for CI to pass

CI runs automatically on the release branch. The script pauses and asks for confirmation.

#### Step 3 — Deploy to Maven Central

```bash
./mvnw spotless:apply
./mvnw -Prelease clean deploy
```

#### Step 4 — Verify and publish to Maven Central

> **Maven Central releases are immutable and cannot be undone or overwritten.**

Before proceeding, verify the pending deployment at [Sonatype Central](https://central.sonatype.com):

- Confirm the version number is correct
- Confirm the expected publishable artifacts are present
- Confirm each publishable artifact has the required JAR, sources, javadoc, and POM files
- Confirm GPG signatures are present
- Confirm Central validation completed successfully

Once verified, publish the deployment from the Maven Central Publishing Portal.

The script pauses for publication confirmation. Answering **N** preserves the pending release; rerun when ready. No rollback or deployment deletion occurs.

#### Step 5 — Tag the release

```bash
git tag -a v<VERSION> -m "Release <VERSION>"
git push origin v<VERSION>
```

#### Step 6 — Bump main to the next development version

```bash
git checkout main
git pull --ff-only
./mvnw versions:set-property -Dproperty=revision -DnewVersion=<VERSION>-POST -DgenerateBackupPoms=false
./mvnw spotless:apply
./mvnw clean install
git add -A
git commit -s -m "chore: Prepare for development"
git push
```

## Conventions

- Release tag: `v<VERSION>`
- Release branch: `release/<VERSION>`
- Post-release development version: `<VERSION>-POST`
- Accepted version format for the release script: `x.y.z` (e.g. `1.2.3`)
- The release version must not already end in `-POST`
- Documentation versions are independent of the release script; see [Publishing Documentation](#publishing-documentation)

## Publishing Documentation

Documentation is published independently of Maven releases. You can publish documentation changes at any time
without releasing to Maven Central, and a release does not publish the site.

```bash
./scripts/publish-documentation.sh              # build and publish
./scripts/publish-documentation.sh --dry-run    # build and show what would be transferred
./scripts/publish-documentation.sh --skip-build --dry-run
```

The script requires the `WWW_ALTCONTAINERS_ORG` environment variable (or `--ssh-host <alias>`), and `rsync`/`ssh`.
It builds the whole site — the unreleased working copy in `website/docs` plus every frozen version in
`website/versioned_docs` — validates it, and rsyncs `website/build` to the documentation host. The build fails if
`website/versions.json` lists a version without content or without a versioned sidebar, if a
`versioned_docs/version-*` directory is not listed, or if `lastVersion` is not registered.

### Documentation versions in the repository

All documentation versions are kept in the repository:

| Path | Purpose |
| ---- | ------- |
| `website/docs` | Working copy for the next release (labeled "Unreleased") |
| `website/versioned_docs/version-<VERSION>/` | Frozen documentation for a released version |
| `website/versioned_sidebars/version-<VERSION>-sidebars.json` | Sidebar for that version |
| `website/versions.json` | Version order, newest first; drives the version dropdown |
| `website/docusaurus.config.js` | `lastVersion` and per-version banners |

The published site must never present unpublished artifacts as current:

- `lastVersion` is always the newest version whose artifacts are published to Maven Central. It has
  `banner: 'none'` and is served at the site root.
- A version that exists in the repository but is not yet published to Maven Central stays in `versions.json` with
  `banner: 'unreleased'`. It appears in the dropdown, is served under `/<VERSION>/`, and directs readers to the
  latest published version.
- Older versions keep `banner: 'unmaintained'` with the `(Unmaintained)` label and `className: 'notice-unmaintained'`.

### Freezing the next version

When the next release's documentation is ready, freeze it before the release is cut:

```bash
cd website
npx docusaurus docs:version <VERSION>
```

This copies `website/docs` to `website/versioned_docs/version-<VERSION>/`, copies `sidebars.js` to
`website/versioned_sidebars/version-<VERSION>-sidebars.json`, and prepends `<VERSION>` to `website/versions.json`.
Then add `'<VERSION>': {banner: 'unreleased'}` to the `versions` map in `website/docusaurus.config.js` so the frozen
version does not become the default before its artifacts exist. Commit both changes.

### Promoting a version after publication

Once the release is published to Maven Central and tagged, promote its documentation:

1. Set `lastVersion: '<VERSION>'` in `website/docusaurus.config.js`.
2. Change that version's entry to `banner: 'none'`.
3. Change the previously selected version to `banner: 'unmaintained'` with
   `label: '<VERSION> (Unmaintained)'` and `className: 'notice-unmaintained'`.
4. Confirm the Maven snippets in `website/versioned_docs/version-<VERSION>/getting-started/installation.md` and
   `.../project-setup.md` name `<VERSION>`.
5. Commit, then run `./scripts/publish-documentation.sh`.

These are ordinary reviewed changes; `scripts/publish-release.sh` neither reads nor modifies documentation versions.

### Troubleshooting documentation publishing

- **Upload fails:** the Maven release is unaffected. Fix credentials/connectivity and rerun
  `./scripts/publish-documentation.sh`. Nothing is deleted.
- **A version is missing from the dropdown:** confirm it is listed in `website/versions.json` and has both
  `website/versioned_docs/version-<VERSION>/` and `website/versioned_sidebars/version-<VERSION>-sidebars.json`.
- **The wrong version is served at the site root:** `lastVersion` in `website/docusaurus.config.js` is not the
  version that should be current; see [Promoting a version after publication](#promoting-a-version-after-publication).

## Manual Release

If the script cannot be used, follow the steps below manually.

### Step 1 — Prepare the release branch

From `main`, create the release branch, set the version, validate the build, then push:

```bash
git checkout main
git pull
git checkout -b release/<VERSION>

./mvnw versions:set-property -Dproperty=revision -DnewVersion=<VERSION> -DgenerateBackupPoms=false

./mvnw spotless:apply
./mvnw clean install

git add -A
git commit -s -m "chore: Release <VERSION>"
git push -u origin release/<VERSION>
```

**If any command in the build fails:** delete the release branch and fix the issue on `main`:

```bash
git checkout main
git branch -D release/<VERSION>
```

Fix the issue on `main`, then start over from Step 1.

### Step 2 — Wait for CI to pass

CI runs automatically on the release branch. Wait for all jobs to pass before proceeding.

See: [GitHub Actions](https://github.com/altcontainers/altcontainers/actions)

**If CI fails:** fix the issue on the release branch, push, and wait for CI to pass again. If the issue requires changes on `main`, delete the release branch (local and remote), fix `main`, and start over:

```bash
git push origin --delete release/<VERSION>
git branch -D release/<VERSION>
```

### Step 3 — Deploy to Maven Central

Once CI is green, deploy the release artifacts to Sonatype Central. The deployment remains pending until you explicitly publish it from the Maven Central Publishing Portal.

```bash
# Ensure you are on the release branch
git checkout release/<VERSION>

./mvnw spotless:apply
./mvnw -Prelease clean deploy
```

### Step 4 — Verify and publish to Maven Central

> **Maven Central releases are immutable and cannot be undone or overwritten.**

Before proceeding, verify the pending deployment at [Sonatype Central](https://central.sonatype.com):

- Confirm the version number is correct
- Confirm the expected publishable artifacts are present
- Confirm each publishable artifact has the required JAR, sources, javadoc, and POM files
- Confirm GPG signatures are present
- Confirm Central validation completed successfully

Once verified, publish the deployment from the Maven Central Publishing Portal.

### Step 5 — Tag the release

After a successful publish, create and push the tag:

```bash
git tag -a v<VERSION> -m "Release <VERSION>"
git push origin v<VERSION>
```

### Step 6 — Publish documentation (separate from the release)

Documentation is published independently of the release with `./scripts/publish-documentation.sh`. See
[Publishing Documentation](#publishing-documentation) for version promotion and troubleshooting.

### Step 7 — Bump main to the next development version

```bash
git checkout main
git pull

./mvnw versions:set-property -Dproperty=revision -DnewVersion=<VERSION>-POST -DgenerateBackupPoms=false

./mvnw spotless:apply
./mvnw clean install

git add -A
git commit -s -m "chore: Prepare for development"
git push
```

## Troubleshooting

### Common Issues

1. **"GPG signing failed"**
   - Verify your GPG key is valid: `gpg --list-secret-keys`
   - Check the key has not expired
   - Ensure the passphrase is correct; GPG agent may be caching it
   - Test manually: `echo "test" | gpg --batch --clearsign`

2. **"Maven settings not found: ~/.m2/settings.xml"**
   - Create or update `~/.m2/settings.xml` with your Sonatype Central credentials
   - Ensure the `<server>` `<id>` matches the Central publishing server ID configured in the POM

3. **"main is not synced with origin/main"**
   - Pull or push to sync: `git pull` or `git push`
   - Check for diverged branches

4. **Existing or conflicting local release tag**
   - Compare the tag commit with the release checkpoint and published artifacts.
   - A matching tag is reused when resuming the tagging step.
   - Without a checkpoint, recover manually; do not delete a published release tag.

5. **Existing or conflicting remote release tag**
   - A matching remote tag is reused after publication has been confirmed.
   - A conflicting tag stops the script. Investigate the release history instead of force-pushing or deleting it.

6. **Central deployment fails**
   - Verify your Sonatype credentials at https://central.sonatype.com
   - Ensure the GPG public key is published to key servers
   - Check that your account has publishing permissions
   - Review Maven output for missing sources, javadoc, signatures, or POM metadata

### Error Recovery

- **Build fails with working-tree changes:** Review and commit or stash the changes, then rerun. The script does not discard work. Resume on `main` or `release/<VERSION>`.
- **CI fails:** Fix and push the release branch, then rerun and confirm CI for the new commit. After CI confirmation, changing the release commit requires manual recovery.
- **Deploy fails or is interrupted:** The script records step 4 *before* uploading, because a failing client may already have created a deployment. Inspect the portal. If the deployment exists, verify and publish it, then rerun to confirm publication. If the version is unpublished and all pending deployments have been dropped (or none exists), run `./scripts/publish-release.sh <VERSION> --execute --retry-deploy` and confirm the retry. The script cannot query Central publication status automatically.
- **Publication is pending or declined:** Branches and checkpoint are retained. Rerun once ready. Nothing is automatically deleted.
- **Tag push fails or is declined:** Rerun; a matching existing local or remote tag is reused. Conflicting tags stop the script.
- **Documentation publishing fails:** unrelated to the release. Fix credentials/connectivity and rerun `./scripts/publish-documentation.sh`; see [Publishing Documentation](#publishing-documentation).
- **Development bump or main push fails:** Review any uncommitted changes, then rerun on `main`. The published release is not redeployed.
- **Release was started with an older script or another clone:** Local checkpoints are unavailable. Follow the remaining manual steps after verifying the release commit and portal state. Do not delete published tags to restart automation. Checkpoints written by an older script version are rejected when their step number is no longer valid.
- **Release lock remains after a forced termination:** Confirm no release process is running, then remove the empty `release-script.lock` directory under `git rev-parse --git-common-dir`. Do not remove an active lock or delete checkpoint files to force a deployment retry.

To validate the orchestration without publishing anything:

```bash
bash -n scripts/publish-release.sh
shellcheck scripts/publish-release.sh
python3 scripts/test-publish-release.py
```

The tests use temporary local Git repositories and mocked build, signing, and
transport commands. They do not build Java artifacts or contact Central/the website.

---

Copyright (c) 2026-present Douglas Hoard
