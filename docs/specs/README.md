# Specs

This folder contains the numbered implementation specs for the SIP softphone restart.

## Folder Format

Each spec lives in:

```text
docs/specs/NNN-short-name/
  spec.md
  implementation.md
  approval.md
```

## Rules

- each spec covers one clear change
- `spec.md` follows an ADR-style structure
- `implementation.md` translates the spec into execution steps
- `approval.md` records who approved the work and when
- specs should move through `draft`, `proposed`, `approved`, and `superseded` as needed

## Ownership

- all agents may read specs
- only the architect may edit specs
- implementation agents should treat specs as the source of truth for approved work

## Numbering

- numbering starts at `001`
- assign the next unused number for a new spec
- keep the folder name short and descriptive

## Relationship to Scenarios

- specs define the change to make
- scenarios define how behavior is validated
