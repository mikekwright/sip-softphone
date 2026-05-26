# 007 Text Injection

- Status: draft
- Spec number: 007
- Date: 2026-05-26

## Context

The target MCP flow must accept text from the client and forward it into the active call.

## Problem

The project needs a clear contract for how text is converted into in-call behavior and which layer owns that conversion.

## Goals

- define the text injection use case
- define shared API inputs and outputs
- separate text submission semantics from transport-specific representations

## Non-goals

- selecting the final synthesis engine
- defining the full media pipeline

## Decision

Specify text injection as a first-class shared call workflow instead of leaving it as an MCP-only convenience behavior.

## Consequences

Both MCP and TUI paths can share the same behavior, but the system must clearly define success, queuing, and interruption semantics.
