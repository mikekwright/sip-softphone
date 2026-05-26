# 006 Transcription Event Model

- Status: draft
- Spec number: 006
- Date: 2026-05-26

## Context

The MCP flow depends on speech capture and text return, and the shared API must carry transcript-oriented updates.

## Problem

The project needs a standard event model for transcript segments, partial results, final results, and related call metadata.

## Goals

- define transcript event types
- define how transcript events are associated with call sessions
- support both streaming observation and completion-oriented workflows

## Non-goals

- selecting the final speech recognition backend
- defining silence completion logic in full

## Decision

Introduce a transcript event model as part of the shared call event system.

## Consequences

Later MCP and TUI work can consume one transcript contract, but the event model must remain stable across transport choices.
