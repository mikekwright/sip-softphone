# 002 Shared Call Control API

- Status: draft
- Spec number: 002
- Date: 2026-05-26

## Context

Both the MCP server and the TUI must use the same softphone behavior without duplicating call lifecycle logic.

## Problem

The project needs a shared Java API for call control, session state, event streaming, and transcript-oriented workflows.

## Goals

- define shared call commands and session types
- define streamed call and transcript events
- keep front-end protocols out of shared domain packages

## Non-goals

- picking the final MCP tool schema
- implementing transport internals

## Decision

Create a Java-first shared API that becomes the common contract for runtime, MCP, and TUI integration.

## Consequences

Later implementation work can converge on one contract, but early discipline is required to avoid leaking UI or protocol concerns into the core.
