# 013 Project Structure and Namespaces

- Status: draft
- Spec number: 013
- Date: 2026-05-26

## Context

The restart will add new modules and packages while preserving legacy modules during migration.

## Problem

The project needs explicit structure and namespace rules so new implementation work does not create dependency drift.

## Goals

- define target modules
- define dependency direction
- define package and adapter boundaries

## Non-goals

- selecting every implementation class
- replacing all legacy code immediately

## Decision

Lock the target structure and package rules before the module count grows.

## Consequences

The repository becomes easier to evolve, but changes to module ownership will need to follow documented boundaries.
