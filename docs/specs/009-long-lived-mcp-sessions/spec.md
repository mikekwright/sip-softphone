# 009 Long-Lived MCP Sessions

- Status: draft
- Spec number: 009
- Date: 2026-05-26

## Context

The target MCP experience requires requests or tool interactions to remain attached while call updates continue to arrive.

## Problem

The project needs explicit semantics for long-lived MCP interactions, including attachment, completion, cancellation, and concurrent observation.

## Goals

- define long-lived MCP interaction semantics
- define how call updates are delivered while a tool remains attached
- define cancellation and teardown behavior

## Non-goals

- final UI details
- low-level transport implementation details outside MCP

## Decision

Specify long-lived MCP sessions as a formal workflow rather than an implementation detail hidden in the server.

## Consequences

The system gains predictable automation behavior, but the implementation must carefully align tool lifecycle with call lifecycle.
