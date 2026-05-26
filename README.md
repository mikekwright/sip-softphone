# SIP Softphone

This repository is being restarted as a Java-first SIP softphone platform with two primary entry points:

- an stdio-only MCP server for LLM-driven phone workflows
- a functional terminal UI that exercises the same shared softphone API

## Project Status

This is a modernization and restart effort.

The current repository still contains a legacy Gradle multi-project layout with `lib/` and `sample-client/`, but the target system is broader than the original library-oriented codebase. The existing code is migration input, not the final architecture.

## Purpose

The project goal is to provide a SIP softphone runtime that can be automated by an MCP client while also remaining usable through a human-operated terminal interface.

The shared runtime must support:

- making a call
- receiving live call updates
- capturing speech from the remote side and returning text
- accepting text from the client and injecting it into the active call
- hanging up the call

## What This Project Is

- a Java-first softphone modernization effort
- a shared call-control and event API for multiple front ends
- a Netty-centered runtime direction
- a repository that will grow specs and scenario-driven workflow documentation

## What This Project Is Not

- not primarily a reusable published SIP library
- not a Kotlin- or Scala-led codebase going forward
- not a GUI desktop client
- not a network-exposed MCP service; the MCP server is stdio-only

## Required End State

The target implementation should provide:

1. a modern Gradle build using a current stable Gradle release
2. a current stable Java baseline, with Java 21 preferred and Java 17 acceptable only if dependency compatibility requires it
3. a shared API that can power both the MCP server and the terminal UI
4. a Netty-aligned runtime model for sessions, timers, and streamed events
5. clear separation between implementation specs and validation scenarios

## Core Functional Requirements

The MCP-facing system must support these workflows:

- start a call
- observe long-lived call progress without losing session context
- listen for remote speech and return transcript events
- wait for remote silence and complete after 3 seconds of silence
- send text into the call flow
- hang up cleanly

The TUI must use the same shared call and event APIs rather than maintaining a separate call stack.

## Current Repository State

### Existing modules

- `lib/`
  - legacy SIP library module
  - contains mixed Java and Kotlin code
  - includes JAIN SIP setup helpers, partial SDP models, and stubbed softphone behavior
- `sample-client/`
  - legacy sample executable
  - currently not a functional client or TUI

### Legacy build constraints

- Gradle 3.3-era conventions
- Java 8 target
- Kotlin 1.0.4
- ScalaTest-era test setup
- deprecated Gradle configurations and plugins
- old publishing and CI assumptions

## Recommended Architecture Direction

The repository should remain multi-project, but responsibilities should shift toward a shared core and focused runtime layers.

### Transitional module direction

- `lib/`
  - short-term migration module
  - source of reusable legacy SIP and SDP knowledge
  - likely reduced over time as new modules take ownership
- `sample-client/`
  - short-term transition harness
  - long-term functional TUI

### Planned module direction

- `softphone-core`
  - shared Java domain API
  - call sessions, commands, events, transcript flow, text injection contracts
- `softphone-runtime` or `softphone-sip`
  - SIP signaling, session lifecycle runtime, timers, media integration, Netty alignment
- `softphone-mcp`
  - stdio MCP server implementation over the shared API
- `sample-client/`
  - TUI that consumes only shared APIs

## Modernization Strategy

Work should proceed incrementally:

1. document the restart direction and workflow
2. stabilize the build around current Gradle and Java
3. define the shared Java API before major implementation work
4. establish the Netty runtime and session model
5. add the stdio MCP server on the shared API
6. evolve `sample-client/` into the functional TUI
7. add scenario-driven validation and operational hardening

## Documentation Map

- `docs/README.md`
  - documentation entry point and folder layout
- `docs/architecture/overview.md`
  - what the project is and the target architecture
- `docs/architecture/project-structure-and-namespaces.md`
  - module layout, package rules, and namespace boundaries
- `docs/architecture/tooling-and-build.md`
  - Java, Gradle, testing, and tooling policy
- `docs/specs/README.md`
  - numbered spec workflow and ownership rules
- `docs/scenarios/README.md`
  - validation and scenario boundary
- `docs/TODO.md`
  - ordered work backlog for completing the restart

## Spec Workflow

This project uses numbered specs under `docs/specs/`.

Each spec lives in its own folder:

```text
docs/specs/NNN-short-name/
  spec.md
  implementation.md
  approval.md
```

Rules:

- `spec.md` is ADR-style and describes one clear change
- `implementation.md` turns the spec into execution steps
- `approval.md` records who approved the spec and when
- all agents may read specs
- only the architect may edit specs

## Risks and Constraints

- legacy JAIN SIP compatibility may constrain the Java baseline
- the current repo does not yet contain a working end-to-end call flow
- media, transcription, and text-injection behavior will require substantial new implementation beyond the legacy library
- MCP long-lived interaction semantics must be defined explicitly before implementation

## Immediate Next Steps

1. approve the initial documentation and spec workflow
2. approve the first build modernization spec
3. modernize the build and establish the Java baseline
4. define the shared call-control and event model
5. implement the runtime, MCP server, and TUI in that order
