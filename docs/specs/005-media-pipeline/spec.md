# 005 Media Pipeline

- Status: draft
- Spec number: 005
- Date: 2026-05-26

## Context

The target platform must capture remote audio and support injected outbound audio or speech behavior, but the legacy repository is mostly signaling-oriented.

## Problem

The project needs a clear boundary for media ownership, RTP-related flow, and how audio reaches transcription and text injection components.

## Goals

- define media ownership boundaries
- define how audio enters and leaves active calls
- keep media decisions separate from MCP transport concerns

## Non-goals

- selecting a final transcription provider
- specifying TUI rendering details

## Decision

Create an explicit media pipeline spec before implementing transcription or injected speech features.

## Consequences

The project avoids hidden media assumptions, but this creates a required design step before advanced call features can ship.
