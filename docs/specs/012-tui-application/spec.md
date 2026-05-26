# 012 TUI Application

- Status: draft
- Spec number: 012
- Date: 2026-05-26

## Context

The project requires a functional terminal UI that validates the shared API outside of MCP.

## Problem

The repository needs a clear plan for how `sample-client/` becomes a real TUI without owning separate call behavior.

## Goals

- define the role of the TUI
- define the required user workflows
- ensure the TUI only consumes shared APIs

## Non-goals

- delivering a GUI
- redefining shared domain contracts

## Decision

Evolve `sample-client/` into the functional TUI and keep it dependent on shared APIs rather than runtime internals.

## Consequences

The TUI becomes a strong integration consumer, but its scope must stay small enough to avoid delaying core runtime work.
