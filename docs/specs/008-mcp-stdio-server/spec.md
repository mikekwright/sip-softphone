# 008 MCP Stdio Server

- Status: draft
- Spec number: 008
- Date: 2026-05-26

## Context

The primary automation interface for this project is an stdio-only MCP server.

## Problem

The project needs a server structure that maps MCP tools onto shared call workflows without bypassing the shared API.

## Goals

- define the stdio-only MCP process model
- define how tools bind to shared call services
- keep MCP-specific request schemas out of the shared domain model

## Non-goals

- implementing long-lived request semantics in detail
- defining TUI workflows

## Decision

Create a dedicated MCP server layer over the shared API, with stdio as the only supported transport.

## Consequences

The automation boundary stays clear, but the server must expose enough structure to support streamed call interactions.
