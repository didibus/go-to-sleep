# Repository engineering adapter

An optional template for `specs/project.md`. If the repo already documents this somewhere, such as
`CLAUDE.md`, point at it instead of writing a second copy that will drift.

## Identity

The main branch, whether RFCs get their own branches, and the current-spec baseline.

## Approval

Who approves designs and accepts implementations, and anything they have delegated (for example,
"commit at every task boundary without asking").

## Local commands

The actual build, focused test, full test, lint, and run commands. Mark an unknown command unknown
rather than guessing something executable.

## Constraints

Secret handling, protected data, which system settings tests may touch, and prohibited actions.
