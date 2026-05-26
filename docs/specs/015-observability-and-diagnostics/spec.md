# 015 Observability and Diagnostics

- Status: draft
- Spec number: 015
- Date: 2026-05-26

## Context

Long-lived calls, streamed events, MCP sessions, and silence-based workflows require strong visibility when failures occur.

## Problem

The project needs a consistent plan for logs, event tracing, and diagnosable runtime behavior.

## Goals

- define logging and correlation expectations
- define which lifecycle events must be observable
- support troubleshooting across runtime and MCP boundaries

## Non-goals

- selecting a full monitoring platform
- implementing every log sink immediately

## Decision

Specify observability requirements early so runtime and server work emit diagnosable behavior from the start.

## Consequences

The system becomes easier to operate and debug, but more structured runtime metadata will be required across modules.
