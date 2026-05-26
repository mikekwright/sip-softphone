# Project Structure and Namespaces

## Repository Shape

The repository should remain a Gradle multi-project build.

## Transitional Module Rules

- `lib/` remains present while legacy SIP code is assessed, extracted, replaced, or retired
- `sample-client/` remains present and evolves into the functional TUI

## Target Module Layout

- `lib/`
  - transitional legacy compatibility and migration source
- `softphone-core`
  - shared domain and application API
- `softphone-runtime` or `softphone-sip`
  - SIP integration and Netty-aligned runtime behavior
- `softphone-mcp`
  - stdio-only MCP server
- `sample-client/`
  - TUI or transition harness that depends on shared APIs only

## Dependency Direction

- `softphone-core` must not depend on MCP, TUI, or legacy client code
- `softphone-mcp` depends on shared core APIs and runtime implementations
- `sample-client/` depends on shared core APIs and runtime implementations
- migration logic that wraps legacy code should sit at the boundary, not leak through shared APIs

## Namespace Rules

Java package naming should remain under:

```text
com.github.mikekwright.sip.softphone
```

Recommended subpackages:

- `.core`
- `.core.call`
- `.core.event`
- `.core.transcript`
- `.runtime`
- `.runtime.sip`
- `.runtime.media`
- `.mcp`
- `.tui`
- `.legacy`

## Boundary Rules

- shared API types should live outside transport-specific packages
- MCP request and response mapping should not leak into shared domain packages
- TUI view and interaction models should not leak into shared domain packages
- legacy JAIN SIP integration should be isolated behind adapters

## Documentation Ownership

- architecture docs explain the structure
- numbered specs define individual approved changes
- scenarios describe validation behavior

Only the architect edits specs. Implementation agents consume approved specs.
