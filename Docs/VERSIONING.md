# Versioning and Release Workflow

## Purpose

This document defines the manual versioning and release workflow for the LEAP trading platform backend and API repository. It is both an onboarding guide and a release checklist.

The backend and API are being combined in this repository. The frontend remains a separate repository and will have its own independent version. A frontend release does not need to match a backend release.

This project uses Java 17, Spring Boot, Maven, Python analytics, Jenkins for CI/CD, and GitHub for source control. Semantic release versions are managed manually. Jenkins build numbers and Git commit SHAs provide automatic identity for individual builds.

## Versioning Strategy

Official releases use Semantic Versioning in this form:

```text
MAJOR.MINOR.PATCH
```

For example:

```text
0.5.2
```

The impact on users and compatibility determines the version change. The number of files or lines changed does not. A one-line behavioral bug fix can justify a PATCH release, while a large test refactor may require no release.

Automatic semantic versioning is intentionally not part of the current workflow. The team selects and applies a version only when preparing an official release.

## Semantic Versioning Rules

### PATCH

Increment PATCH for backwards-compatible bug fixes.

```text
0.5.2 to 0.5.3
```

### MINOR

Increment MINOR for new backwards-compatible functionality. Reset PATCH to zero.

```text
0.5.2 to 0.6.0
```

### MAJOR

Increment MAJOR for breaking or incompatible changes. Reset MINOR and PATCH to zero.

```text
0.5.2 to 1.0.0
```

Documentation-only changes, test-only changes, refactoring, CI changes, formatting changes, and similar maintenance work generally do not justify a release by themselves.

## Release Version vs. Build Identity

A semantic release version answers:

> What version of the product is this?

```text
Version: 0.5.0
```

Build identity answers:

> Which exact CI build and source commit produced this artifact?

Jenkins supplies a build number, and Git supplies a commit SHA:

```text
Version: 0.5.0
Build: 94
Commit: fa829c1
```

Developers do not manually increment Jenkins `BUILD_NUMBER` or create Git commit SHAs. The semantic version changes only during the release process. Jenkins build numbers may increase many times while the semantic version remains unchanged:

```text
Release version: 0.5.0
Jenkins build: 91

Release version: 0.5.0
Jenkins build: 92

Release version: 0.5.0
Jenkins build: 93

Release version: 0.5.1
Jenkins build: 94
```

Every CI build does not need a new semantic version.

## Source of Truth

The authoritative source for the application semantic version is the project-level `<version>` in `pom.xml`.

The Maven project coordinates currently found in this repository are:

```xml
<groupId>com.group12</groupId>
<artifactId>group-12-backend</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

The parent Spring Boot version is dependency and build configuration, not the application release version. Do not treat the parent version as the LEAP backend version.

Do not introduce `version.txt`. Do not duplicate the application version across Java files, YAML files, README files, Jenkins configuration, or other files. Keeping one manually maintained source of truth prevents version mismatches.

## Normal Development Workflow

Developers must not change the semantic version during normal development. Work is performed on branches and merged into protected `main` through Pull Requests.

Examples of normal PR commit messages include:

```text
feat(order): add order history
fix(order): prevent duplicate submissions
docs: correct API example
test(order): improve validation coverage
refactor(order): extract validation service
ci: update Jenkins pipeline
```

These PRs are merged without modifying the project version in `pom.xml`. The version changes only when the team intentionally prepares a release.

## When to Create a Release

The team may prepare a release when the current state of `main` is ready to become an official product version. Review all meaningful changes since the previous release and select the next version according to the highest-impact change:

* Fixes only require PATCH.
* New backwards-compatible functionality requires MINOR.
* A breaking or incompatible change requires MAJOR.

Maintenance work alone generally does not require a new release. Release timing is an intentional team decision, not an automatic result of merging a PR or completing a Jenkins build.

## Manual Release Procedure

### 1. Complete normal development

Merge intended development work into `main` through approved Pull Requests.

### 2. Review the release contents

Review meaningful changes since the previous release. Confirm that `main` contains everything intended for the release.

### 3. Select the next version

Choose PATCH, MINOR, or MAJOR based on compatibility and behavior:

* Fixes only: PATCH
* New backwards-compatible functionality: MINOR
* Breaking change: MAJOR

### 4. Update local main

```bash
git checkout main
git pull
```

### 5. Create a release branch

```bash
git checkout -b release/vX.Y.Z
```

Example:

```bash
git checkout -b release/v0.5.0
```

### 6. Update `pom.xml`

Change only the project-level version to the selected release version.

Before:

```xml
<version>0.4.2</version>
```

After:

```xml
<version>0.5.0</version>
```

### 7. Update the changelog

This repository does not currently contain `CHANGELOG.md`. When the team adopts this workflow for a release, create it at the repository root and maintain it for subsequent releases. This documentation task does not create that file.

A release entry should follow this general form:

```markdown
# Changelog

## [0.5.0] - YYYY-MM-DD

### Added
* Added client order history functionality.

### Fixed
* Prevented duplicate order submissions.

### Changed
* Improved relevant application behavior or infrastructure where appropriate.
```

Replace `YYYY-MM-DD` with the release date. Summarize meaningful release-level changes instead of copying every Git commit.

### 8. Commit the release preparation

Because `CHANGELOG.md` does not currently exist, add it when adopting the workflow, then commit both release files:

```bash
git add pom.xml CHANGELOG.md
git commit -m "chore(release): prepare v0.5.0"
```

If the team deliberately defers creating a changelog, stage only the file that exists:

```bash
git add pom.xml
git commit -m "chore(release): prepare v0.5.0"
```

### 9. Push the release branch

```bash
git push origin release/v0.5.0
```

### 10. Open the release Pull Request

Open a Pull Request with this direction:

```text
release/v0.5.0 to main
```

Suggested title:

```text
chore(release): prepare v0.5.0
```

### 11. Review and merge

The release PR follows the normal protected-branch policy:

* Jenkins checks must pass where applicable.
* Two approvals are required.
* Reviewers verify the selected version and release notes before merging.

### 12. Update local main after the merge

```bash
git checkout main
git pull
```

### 13. Create an annotated Git tag

Create the tag from the updated `main` so it points to the merged release commit:

```bash
git tag -a v0.5.0 -m "Release v0.5.0"
```

### 14. Push the tag

```bash
git push origin v0.5.0
```

### 15. Create the GitHub Release

Create a GitHub Release associated with the same tag:

```text
Tag: v0.5.0
Release title: v0.5.0
```

Release notes should summarize the same meaningful changes recorded in `CHANGELOG.md`.

### 16. Verify all identifiers

Confirm that every release identifier agrees:

```text
pom.xml:        0.5.0
CHANGELOG.md:   0.5.0
Git tag:        v0.5.0
GitHub Release: v0.5.0
```

## Changelog Guidelines

`CHANGELOG.md` should help readers understand product changes without reading the entire commit history.

* Include meaningful release-level behavior, features, fixes, and relevant infrastructure changes.
* Group entries under clear headings such as `Added`, `Fixed`, and `Changed`.
* Write entries from the perspective of their effect on the product or its operation.
* Do not copy every commit into the changelog.
* Keep the changelog version and date aligned with the official release.

## Git Tags and GitHub Releases

Release versions use `X.Y.Z` in `pom.xml`. Git tags and GitHub Releases use the corresponding `vX.Y.Z` form.

```text
Application version: 0.5.0
Git tag:             v0.5.0
GitHub Release:      v0.5.0
```

The annotated tag identifies the exact release commit. The GitHub Release provides a discoverable release record and human-readable notes. Every official release should have both.

## Full Release Example

Assume the current official release is:

```text
v0.5.0
```

The team merges these changes through normal PRs:

```text
fix(trading): reject orders with insufficient cash
docs: clarify order API documentation
test(trading): add insufficient cash tests
fix(auth): correct expired session handling
```

The semantic version remains `0.5.0` during normal development. When the team decides to release, the highest meaningful change is a backwards-compatible bug fix, so the next version is PATCH: `0.5.1`.

Create the branch:

```bash
git checkout main
git pull
git checkout -b release/v0.5.1
```

Update the project version in `pom.xml`:

```text
0.5.0 to 0.5.1
```

Add the changelog entry:

```markdown
## [0.5.1] - YYYY-MM-DD

### Fixed
* Rejected trading orders when the client has insufficient cash.
* Corrected expired session handling.

### Changed
* Clarified order API documentation.

### Tests
* Added coverage for insufficient-cash order validation.
```

Commit and push the release preparation:

```bash
git add pom.xml CHANGELOG.md
git commit -m "chore(release): prepare v0.5.1"
git push origin release/v0.5.1
```

After the release PR receives two approvals, passes applicable Jenkins checks, and is merged, create the release tag:

```bash
git checkout main
git pull
git tag -a v0.5.1 -m "Release v0.5.1"
git push origin v0.5.1
```

Create the matching GitHub Release:

```text
Tag: v0.5.1
Release title: v0.5.1
```

## Team Versioning Policy

1. Do not change the semantic version in normal feature, fix, documentation, test, refactor, CI, or maintenance PRs.
2. Only a dedicated release PR should manually change the project version in `pom.xml`.
3. `pom.xml` is the authoritative source for the Maven application version.
4. Use Semantic Versioning when selecting the next release.
5. Release versions use `X.Y.Z`.
6. Git tags use `vX.Y.Z`.
7. Every official release should have a corresponding Git tag.
8. Every official release should have a corresponding GitHub Release.
9. `CHANGELOG.md` should describe meaningful release-level changes.
10. Jenkins `BUILD_NUMBER` and the Git SHA identify individual builds and must not be confused with the semantic release version.
11. Each repository is versioned independently. Future frontend releases do not need to match backend releases.

## Release Checklist

* [ ] Confirm `main` contains everything intended for the release
* [ ] Review changes since the previous release
* [ ] Determine PATCH, MINOR, or MAJOR
* [ ] Create the `release/vX.Y.Z` branch
* [ ] Update the project-level version in `pom.xml`
* [ ] Update `CHANGELOG.md`
* [ ] Commit using `chore(release): prepare vX.Y.Z`
* [ ] Push the release branch
* [ ] Open the release PR into `main`
* [ ] Confirm applicable Jenkins checks pass
* [ ] Obtain two approvals
* [ ] Merge the release PR
* [ ] Pull the updated `main`
* [ ] Create the annotated `vX.Y.Z` Git tag
* [ ] Push the Git tag
* [ ] Create the matching GitHub Release
* [ ] Verify `pom.xml`, `CHANGELOG.md`, the Git tag, and the GitHub Release all use the same version
