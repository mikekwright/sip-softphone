# 004 SIP Adapter Boundary

- Status: draft
- Spec number: 004
- Date: 2026-05-26

## Context

The repository contains legacy JAIN SIP-oriented code in `lib/`, but the restart should avoid binding the full system to legacy implementation details.

## Problem

The project needs a clear migration boundary between shared softphone behavior and legacy or replacement SIP signaling internals.

## Goals

- isolate SIP signaling behind adapters
- define how `lib/` is reused, wrapped, replaced, or retired
- keep shared APIs independent from JAIN SIP types

## Non-goals

- deciding all media behavior
- implementing the full runtime

## Decision

Establish a formal SIP adapter boundary so legacy code can be incrementally migrated without contaminating shared APIs.

## Consequences

Migration work becomes more predictable, but adapter design must account for incomplete legacy behavior.
