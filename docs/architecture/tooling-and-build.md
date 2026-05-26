# Tooling and Build

## Language Policy

- primary implementation language: Java
- preferred baseline: Java 21 LTS
- acceptable fallback: Java 17 LTS if dependency compatibility requires it during migration
- Kotlin and Scala should not be expanded for new project work unless a spec explicitly approves an exception

## Build Policy

- use the Gradle wrapper
- upgrade from the legacy Gradle 3.3-era setup to a current stable Gradle release
- use Java toolchains
- prefer `java-library` and `application` plugins over legacy patterns
- replace deprecated configurations such as `compile` and `testCompile`

## Testing Policy

- use JUnit 5 for new automated tests
- keep validation runnable from the Gradle wrapper
- separate implementation specs from validation scenarios
- add scenario-oriented coverage under `docs/scenarios/`

## Runtime Policy

- use Netty as the runtime focus for sessions, timers, and event-oriented flow
- keep asynchronous behavior aligned behind clear runtime boundaries
- do not let MCP or TUI front ends own call lifecycle logic directly

## Legacy Migration Policy

- treat `lib/` as a migration source, not the final architecture
- treat `sample-client/` as a transition harness and TUI landing zone
- remove or retire old publishing, CI, and plugin assumptions when no longer needed

## Tooling Expectations

- Gradle wrapper for build and test commands
- modern dependency management
- structured logging suitable for long-lived call sessions
- documentation-first workflow through `docs/specs/` and `docs/scenarios/`

## Out of Scope for Initial Restart

- GUI application work
- public network-exposed MCP transport
- broad library publishing concerns
