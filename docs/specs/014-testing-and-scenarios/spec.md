# 014 Testing and Scenarios

- Status: draft
- Spec number: 014
- Date: 2026-05-26

## Context

The restart needs automated validation and documented end-to-end scenarios, but specs and validation artifacts serve different purposes.

## Problem

The project needs a clear testing strategy and a boundary between implementation specs and scenario-driven verification.

## Goals

- define automated test layers
- define what belongs in `docs/scenarios/`
- define how silence-based and long-lived workflows are validated

## Non-goals

- implementing every scenario immediately
- replacing build modernization work

## Decision

Create one testing and scenario boundary spec so implementation planning and validation planning remain separate.

## Consequences

Verification becomes more coherent, but the repo must maintain both code-level and doc-level validation artifacts.
