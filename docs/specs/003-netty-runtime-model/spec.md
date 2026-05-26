# 003 Netty Runtime Model

- Status: draft
- Spec number: 003
- Date: 2026-05-26

## Context

The target system needs a coherent runtime model for session ownership, timers, event dispatch, and long-lived interactions.

## Problem

Without a clear runtime boundary, SIP behavior, MCP behavior, and TUI behavior may each create separate concurrency models.

## Goals

- make Netty the runtime focus
- define session lifecycle and event dispatch boundaries
- establish where timers and backpressure are owned

## Non-goals

- completing SIP adapter migration
- finalizing UI behavior

## Decision

Adopt a Netty-aligned runtime model that owns asynchronous session flow beneath the shared API.

## Consequences

The project gains one event model for shared behavior, but legacy code must be adapted rather than allowed to define runtime patterns ad hoc.
