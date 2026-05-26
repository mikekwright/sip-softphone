# Docs

This folder contains the future-facing documentation for the SIP softphone restart.

## Layout

- `architecture/`
  - project-level architecture documents
  - the primary source for what the project is, how it is structured, and which tooling it uses
- `specs/`
  - numbered ADR-style work items
  - each spec describes one change and includes implementation and approval companions
- `scenarios/`
  - validation-oriented behavior documents and end-to-end workflow checks
- `TODO.md`
  - ordered backlog for completing the restart

## Reading Order

1. `../README.md`
2. `architecture/overview.md`
3. `architecture/project-structure-and-namespaces.md`
4. `architecture/tooling-and-build.md`
5. `specs/README.md`
6. `TODO.md`

## Workflow Boundaries

- architecture docs explain the target system and project rules
- specs define approved implementation changes
- scenarios define validation expectations and operator-facing behavior

Implementation work should follow approved specs. Scenario documents should not be used as substitutes for specs.
