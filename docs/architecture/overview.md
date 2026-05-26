# Architecture Overview

## Project Definition

This project is a Java-first SIP softphone platform under restart from a legacy library-oriented repository.

Its target purpose is to expose a shared softphone runtime through:

- an stdio-only MCP server for LLM automation
- a functional terminal UI for direct human use

## Architectural Priorities

1. modernize the repository to current stable Java and Gradle
2. keep the implementation primarily in Java
3. use Netty as the runtime direction for session and event orchestration
4. preserve and evaluate `lib/` and `sample-client/` as migration assets
5. keep one shared API for both MCP and TUI workflows

## Core Capabilities

The target system must support:

- call initiation
- call lifecycle management
- transcript-oriented listening workflows
- text injection into an active call
- hangup and teardown
- long-lived call interactions for MCP clients
- a listen-until-silence workflow that completes after 3 seconds of silence

## Logical Architecture

### Shared core

Owns domain concepts and workflow contracts:

- call sessions
- commands
- events
- transcript segments
- text injection contracts

### Runtime and SIP layer

Owns transport and runtime concerns:

- SIP signaling
- session timers
- event dispatch
- media integration boundaries
- Netty-aligned execution model

### MCP layer

Owns stdio MCP behavior:

- tool registration
- request/session attachment
- event streaming to clients
- cancellation and completion semantics

### TUI layer

Owns operator workflows:

- call creation
- call observation
- transcript display
- text submission
- manual hangup

## Migration Position

The existing `lib/` and `sample-client/` modules are treated as transition points.

- `lib/` is the initial migration source for SIP abstractions and protocol knowledge
- `sample-client/` is the initial landing zone for a future TUI

The restart should avoid a one-shot rewrite. Build modernization and shared API design should happen before major runtime expansion.
