# Changelog

All notable changes to the LEAP trading platform backend and API will be documented in this file.

This project follows [Semantic Versioning](https://semver.org/). Release versions are prepared manually according to the process in [Docs/VERSIONING.md](Docs/VERSIONING.md).

## [Unreleased]

Changes are collected here during normal development. When the team prepares a release, move the relevant entries into a new versioned section with the release date.

### Added

- Established the Java 17 and Spring Boot Maven project under the `com.group12:group-12-backend` coordinates.
- Added an initial REST endpoint and endpoint test as the foundation for backend API development.
- Added PostgreSQL schema and seed scripts for instruments, clients, administrators, orders, and holdings.
- Added Python business analytics for client counts, traded instruments, instrument prices, order statuses, and yearly trade volume.
- Added CSV data exports and PDF visualizations for business analytics results.
- Added a Jenkins smoke-test pipeline with checkout, sanity-check, build, and test stages.
- Added the Mermaid UML class diagram for the trading domain.
- Added the manual semantic versioning and release workflow documentation.

### Changed

- Consolidated client identity and cash balance data in the database schema.
- Updated repository ignore rules for local environment and IDE files.

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
