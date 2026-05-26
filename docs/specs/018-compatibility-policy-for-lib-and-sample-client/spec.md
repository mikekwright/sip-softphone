# 018 Compatibility Policy for Lib and Sample Client

- Status: draft
- Spec number: 018
- Date: 2026-05-26

## Context

The legacy `lib/` and `sample-client/` modules remain important migration anchors, but their long-term responsibilities will change.

## Problem

The project needs an explicit compatibility policy so modernization work can proceed without confusion about how long these modules remain authoritative.

## Goals

- define the transitional role of `lib/`
- define the transitional role of `sample-client/`
- define rename, deprecation, or retention expectations

## Non-goals

- performing every module move immediately
- redefining the complete shared API

## Decision

Create a compatibility policy that preserves migration context while allowing new modules to take ownership over time.

## Consequences

The migration path becomes clearer, but implementation work must follow explicit ownership changes instead of ad hoc restructuring.
