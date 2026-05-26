# 017 Packaging and Developer Workflow

- Status: draft
- Spec number: 017
- Date: 2026-05-26

## Context

The restart will need repeatable local workflows for building, running, and verifying the MCP server and TUI.

## Problem

The project needs a packaging and developer workflow plan that replaces outdated publishing and CI assumptions.

## Goals

- define local run workflows
- define packaging expectations for MCP and TUI entry points
- define wrapper-based developer commands

## Non-goals

- designing a public distribution strategy in full
- finalizing production deployment

## Decision

Create a dedicated packaging and developer workflow spec so modern local usage becomes part of the restart rather than an afterthought.

## Consequences

Developers gain a clearer workflow, but packaging choices must follow the build modernization and module structure decisions.
