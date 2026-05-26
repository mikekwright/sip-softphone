# 011 Call State Machine

- Status: draft
- Spec number: 011
- Date: 2026-05-26

## Context

Call setup, hangup, transcription, MCP observation, and TUI actions all depend on a consistent call lifecycle.

## Problem

The project needs a formal call state model with failure, teardown, and cancellation semantics.

## Goals

- define call states and state transitions
- define failure and cancellation handling
- define which events are emitted for each lifecycle transition

## Non-goals

- full server implementation
- packaging decisions

## Decision

Create a call state machine spec that becomes the lifecycle contract for runtime and front ends.

## Consequences

Shared behavior becomes predictable, but runtime and MCP implementation work must follow the same lifecycle rules.
