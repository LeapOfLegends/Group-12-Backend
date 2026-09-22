# Changelog

All notable changes to the LEAP trading platform backend and API will be documented in this file.

This project follows [Semantic Versioning](https://semver.org/). Release versions are prepared manually according to the process in [Docs/VERSIONING.md](Docs/VERSIONING.md).

## [Unreleased]

Changes are collected here during normal development. When the team prepares a release, move the relevant entries into a new versioned section with the release date.

### Added


### Changed


## [0.1.0] - 2026-09-22

### Added

- Client REST API endpoints for creating, updating, and retrieving clients (by id and email), plus a client-specific orders endpoint.
- ClientService business logic and ClientDTO integration to validate and transform client data for persistence.
- ClientRepository persistence methods and database seed entries for clients.
- Unit tests covering client endpoint validation and basic client workflows.

### Changed

- Consolidated client and account data models (client/account join) and updated seed scripts accordingly.
- Exposed client counts to analytics and added environment configuration support for client components.


## Release Entry Template

Copy this section when preparing an official release. Replace the placeholders, remove unused headings, and move applicable entries from `Unreleased` into the new release section.

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added

- Describe new backwards-compatible functionality.

### Changed

- Describe meaningful changes to existing behavior or infrastructure.

### Fixed

- Describe backwards-compatible bug fixes.

### Removed

- Describe removed functionality, including any breaking impact.

### Security

- Describe security-related changes without exposing sensitive details.
```

Changelog entries should summarize meaningful release-level changes rather than repeat every commit message.
