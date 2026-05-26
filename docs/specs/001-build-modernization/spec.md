# 001 Build Modernization

- Status: draft
- Spec number: 001
- Date: 2026-05-26

## Context

The repository is still using a Gradle 3.3-era build, Java 8 targets, Kotlin 1.0.4, ScalaTest-era setup, and deprecated Gradle conventions. This blocks the restart and makes later runtime work risky.

## Problem

The build baseline is too old to support a stable Java-first modernization path.

## Goals

- upgrade to a current stable Gradle wrapper
- establish the supported Java baseline
- remove obsolete Gradle configurations and plugins that are not part of the target architecture

## Non-goals

- delivering MCP functionality
- redesigning runtime APIs

## Decision

Modernize the build first while preserving the multi-project shape and keeping `lib/` and `sample-client/` available during migration.

## Consequences

The repository gains a maintainable baseline for the remaining specs, but some legacy tests or plugins may need removal or replacement.
