# 010 Listen Until Silence

- Status: draft
- Spec number: 010
- Date: 2026-05-26

## Context

One listening-oriented workflow must wait for remote speech, continue listening, and complete after 3 seconds of silence.

## Problem

The silence-based completion behavior needs precise semantics before implementation.

## Goals

- define the 3-second silence completion workflow
- define what starts and stops listening
- define what result is returned on completion or cancellation

## Non-goals

- choosing the final speech recognition backend
- redefining the whole transcript event model

## Decision

Create a dedicated workflow spec for listen-until-silence so timing, completion, and failure semantics are explicit.

## Consequences

This avoids ambiguous listening behavior, but the implementation must coordinate timers, transcript events, and call state carefully.
