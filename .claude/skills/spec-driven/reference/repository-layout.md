# Repository layout

```text
specs/
  project.md                         # optional repo adapter: approver, commands, constraints
  current/
    architecture/overview.md
    ...                              # whatever sections the system actually needs
  changes/
    last-number.md                   # highest RFC number ever allocated
    active/
      0003-relock-on-unlock/
        RFC.md
        tasks.md
        notes.md
    archive/
      0002-schedule-editor/...
```

Create only the sections the repository actually needs. Keep templates inside the installed skill
rather than copying them into the repo. Do not scaffold empty directories or README files.

For a project whose RFC identity must survive checkout-directory renames, add
`specs/project-id.txt`. It contains one newline-terminated lowercase kebab-case identifier, such as:

```text
go-to-sleep
```

When the file is present, `rfc.py` uses it for RFC IDs and status output. Repositories without the
file retain the package-directory-name behavior.

## Identity and numbering

A change folder is `NNNN-short-slug`. The slug is for humans reading a directory listing. The RFC ID
is `<project-id>/NNNN` when `specs/project-id.txt` exists, otherwise `<package>/NNNN`, where the
package is the directory holding `specs/`. The ID survives archiving and checkout renames; the path
does not.

Create a change with
`python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" new "<slug>"`,
never by hand. It allocates one above the higher of `last-number.md` and the highest folder number
under `active/` or `archive/`. It then records that number in `last-number.md` and scaffolds `RFC.md`,
`tasks.md`, and `notes.md` from `assets/`. The changes are left uncommitted.

**One RFC, one number.** Four digits, no letters, no suffixes: `0001a`/`0001b` is not a split of `0001`.
Two changes are two numbers. Numbers are never freed or reused — archiving keeps the folder, and a
rejected RFC is archived, not deleted. Do not promise a number in a plan before `new` has allocated it.

| Need | Command |
| --- | --- |
| Allocate a number and scaffold the folder | `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" new "short-slug"` |
| Stamp Started / today into the Progress block | `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" day NNNN` |
| See every RFC with its phase, estimate, and dates | `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" status` |
| Check numbering and folder names | `python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" check [--fix]` |

`--package DIR` defaults to the nearest directory at or above the working directory that has
`specs/changes/`.

## The write guard

The script's `hook` command is a `PreToolUse` guard on writes under `specs/changes/active/`. It blocks
writing into a folder whose number is above `last-number.md` — a number `new` never allocated. It also
blocks a change folder whose name does not start `NNNN-`. Register it in `.claude/settings.json`:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/skills/spec-driven/scripts/rfc.py\" hook",
            "timeout": 10
          }
        ]
      }
    ]
  }
}
```

It fails open on anything it does not understand. The script's `check` command catches whatever got
past it, such as a folder created in a text editor.
