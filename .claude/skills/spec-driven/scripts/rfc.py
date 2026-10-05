#!/usr/bin/env python3
"""Mechanical parts of the RFC loop: numbering, day stamps, status, checks.

Stdlib only, and local only: nothing here fetches, commits, or pushes. `specs/changes/last-number.md`
records the highest number ever allocated in a package; `new` allocates one above the higher of that
and the highest folder under active/ or archive/, so a number is never reused. The protocol is in
spec-driven/reference/repository-layout.md.

Commands:
  new SLUG      allocate the next number and scaffold the folder
  day RFC       stamp Started / today's date into the Progress block (idempotent)
  status        every RFC with its phase, estimate, dates, and day count
  check         verify numbering and folder names (--fix raises last-number.md)
  hook          PreToolUse guard; reads the tool payload on stdin

Run any command with --help for its options.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

NUM = re.compile(r"^(\d{4,})(?:-|$)")
PROJECT_ID = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")

# ---------------------------------------------------------------------------- git


class Abort(RuntimeError):
    pass


def git_root(start: Path) -> Path:
    p = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=start,
        capture_output=True,
        text=True,
        stdin=subprocess.DEVNULL,
    )
    if p.returncode != 0:
        raise Abort(f"{start} is not inside a git checkout")
    return Path(p.stdout.strip())


def git_ignored(root: Path, path: Path) -> bool:
    rel = str(path.relative_to(root)) if path != root else "."
    p = subprocess.run(
        ["git", "check-ignore", "--quiet", "--", rel],
        cwd=root,
        capture_output=True,
        stdin=subprocess.DEVNULL,
    )
    return p.returncode == 0


# ------------------------------------------------------------------- packages, numbers


def changes_dir(pkg: Path) -> Path:
    return pkg / "specs" / "changes"


def resolve_package(where: Path, root: Path, create: bool = False) -> Path:
    """Nearest ancestor holding specs/changes/; when creating, fall back to the checkout root."""
    for candidate in [where, *where.parents]:
        if changes_dir(candidate).is_dir():
            return candidate
        if candidate == root:
            break
    if create:
        return root
    raise Abort(
        f"no specs/changes/ at or above {where}. Create the first RFC with `rfc.py new`, "
        "or pass --package."
    )


def all_packages(root: Path) -> list[Path]:
    """Every package with a specs/changes/, minus whatever git ignores — a nested worktree or a
    vendored copy of another repo holds RFCs that are not this checkout's to count."""
    found = sorted({p.parent.parent for p in root.glob("**/specs/changes") if p.is_dir()})
    return [pkg for pkg in found if not git_ignored(root, pkg)]


def counter_file(pkg: Path) -> Path:
    return changes_dir(pkg) / "last-number.md"


def project_identifier(pkg: Path) -> str:
    """Stable RFC namespace from specs/project-id.txt, falling back to the checkout/package name."""
    path = pkg / "specs" / "project-id.txt"
    if path.is_symlink():
        raise Abort(f"{path} must be a regular file")
    if not path.exists():
        return pkg.name
    if not path.is_file():
        raise Abort(f"{path} must be a regular file")
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise Abort(f"{path} must be readable UTF-8") from exc
    if not raw.endswith("\n") or raw.count("\n") != 1:
        raise Abort(f"{path} must contain one newline-terminated project identifier")
    value = raw[:-1]
    if not PROJECT_ID.fullmatch(value):
        raise Abort(
            f"{path} must contain a lowercase kebab-case project identifier"
        )
    return value


def read_counter(pkg: Path) -> int:
    f = counter_file(pkg)
    if not f.exists():
        return 0
    m = re.search(r"\d{4,}", f.read_text())
    return int(m.group()) if m else 0


def highest_folder(pkg: Path) -> tuple[int, str | None]:
    best, name = 0, None
    for state in ("active", "archive"):
        d = changes_dir(pkg) / state
        if not d.is_dir():
            continue
        for entry in d.iterdir():
            m = NUM.match(entry.name)
            if m and int(m.group(1)) > best:
                best, name = int(m.group(1)), f"{state}/{entry.name}"
    return best, name


def malformed_folders(pkg: Path) -> list[str]:
    """Change folders that do not start `NNNN-`. `0001a-thing` is not a variant spelling of a number —
    it is invisible to the counter, to this check and to the write guard. One RFC, one number."""
    found = []
    for state in ("active", "archive"):
        d = changes_dir(pkg) / state
        if not d.is_dir():
            continue
        found += [
            f"{state}/{e.name}"
            for e in sorted(d.iterdir())
            if e.is_dir() and not e.name.startswith(".") and not NUM.match(e.name)
        ]
    return found


def find_rfc(pkg: Path, ident: str) -> Path:
    """Accept a number (`3`, `0003`), a folder name, an RFC ID (`pkg/0003`), or a path."""
    direct = Path(ident)
    if direct.is_dir() and (direct / "RFC.md").exists():
        return direct.resolve()
    token = ident.rsplit("/", 1)[-1]
    head = token.split("-")[0]
    wanted = int(head) if head.isdigit() else None
    for state in ("active", "archive"):
        d = changes_dir(pkg) / state
        if not d.is_dir():
            continue
        for entry in sorted(d.iterdir()):
            m = NUM.match(entry.name)
            if entry.name == token or (wanted is not None and m and int(m.group(1)) == wanted):
                return entry
    raise Abort(f"no RFC matching {ident!r} under {changes_dir(pkg)}")


# ------------------------------------------------------------------- markdown tables


def row_pattern(field: str) -> re.Pattern:
    """People bold and quote these labels by hand, so `| **Phase** |` is the same row as
    `| Phase |`. Group 1 is the label cell, kept verbatim so a rewrite preserves the styling."""
    mark = r"[*_`]{0,2}"
    return re.compile(rf"^(\|\s*{mark}{re.escape(field)}{mark}\s*\|)(.*)(\|\s*)$", re.IGNORECASE)


def read_row(path: Path, field: str) -> str | None:
    if not path.exists():
        return None
    pattern = row_pattern(field)
    for line in path.read_text().splitlines():
        m = pattern.match(line)
        if m:
            return m.group(2).strip()
    return None


def write_row(path: Path, field: str, value: str) -> bool:
    pattern = row_pattern(field)
    lines = path.read_text().splitlines(keepends=True)
    for i, line in enumerate(lines):
        m = pattern.match(line.rstrip("\n"))
        if m:
            end = "\n" if line.endswith("\n") else ""
            lines[i] = f"{m.group(1)} {value} |{end}"
            path.write_text("".join(lines))
            return True
    return False


# Template placeholders count as empty too, or the first day stamp would never land.
EMPTY = {"", "—", "-", "–", "tbd", "todo", "none", "yyyy-mm-dd"}


def is_empty(value: str | None) -> bool:
    return value is None or value.strip().lower() in EMPTY


def cell(value: str | None) -> str:
    """A template placeholder is not data — an unanswered row reads as blank rather than parroting
    the template back as though somebody had filled it in. Pipes are escaped so the table survives."""
    if is_empty(value) or "YYYY-MM-DD" in value or "person-days — basis" in value:
        return "—"
    return value.replace("|", "\\|")


# ---------------------------------------------------------------------------- commands


def template_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "assets"


def scaffold(folder: Path, ident: str, number: str) -> list[str]:
    """Copy the templates in, filling the only placeholders the script knows better than the author
    does: the number and the RFC ID. A title of `RFC NNNN` should never survive into a committed doc."""
    copied = []
    for name in ("RFC.md", "tasks.md", "notes.md"):
        dest = folder / name
        src = template_dir() / name.lower()
        if dest.exists() or not src.exists():
            continue
        shutil.copyfile(src, dest)
        text = dest.read_text().replace("package-slug/NNNN", ident).replace("NNNN", number)
        dest.write_text(text)
        copied.append(name)
    return copied


def cmd_new(args, root: Path) -> int:
    pkg = resolve_package(Path(args.package or Path.cwd()).resolve(), root, create=True)
    slug = re.sub(r"[^a-z0-9]+", "-", args.slug.lower()).strip("-")
    if not slug:
        raise Abort("give the change a slug — the folder name is how a human finds it")
    n = max(read_counter(pkg), highest_folder(pkg)[0]) + 1
    changes_dir(pkg).mkdir(parents=True, exist_ok=True)
    counter_file(pkg).write_text(f"{n:04d}\n")
    folder = changes_dir(pkg) / "active" / f"{n:04d}-{slug}"
    folder.mkdir(parents=True, exist_ok=True)
    copied = (
        []
        if args.no_templates
        else scaffold(folder, f"{project_identifier(pkg)}/{n:04d}", f"{n:04d}")
    )
    print(f"RFC {n:04d}: {folder.relative_to(root)}")
    if copied:
        print(f"  scaffolded {', '.join(copied)} — fill them in")
    print("  last-number.md and the folder are uncommitted")
    return 0


def cmd_day(args, root: Path) -> int:
    pkg = resolve_package(Path(args.package or Path.cwd()).resolve(), root)
    rfc = find_rfc(pkg, args.rfc)
    today = args.date or datetime.date.today().isoformat()
    doc = rfc / "tasks.md"
    if not doc.exists():
        raise Abort(f"{doc.relative_to(root)} does not exist — there is no Progress block to stamp")
    changed = []
    if is_empty(read_row(doc, "Started")) and write_row(doc, "Started", today):
        changed.append(f"Started = {today}")
    days = read_row(doc, "Days worked")
    if days is not None and today not in days:
        value = today if is_empty(days) else f"{days.strip()}, {today}"
        if write_row(doc, "Days worked", value):
            changed.append(f"+{today}")
    if not changed:
        print(f"{rfc.name}: already stamped for {today}")
    else:
        for line in changed:
            print(f"{rfc.name} — tasks.md: {line}")
        print("  not committed — it goes up with the work you did today")
    return 0


def cmd_status(args, root: Path) -> int:
    packages = [resolve_package(Path(args.package).resolve(), root)] if args.package else all_packages(root)
    if not packages:
        print("no specs/changes/ in this checkout yet — `rfc.py new <slug>` creates the first RFC")
        return 0
    rows, drift = [], []
    for pkg in packages:
        identifier = project_identifier(pkg)
        counter, (folder_max, folder_name) = read_counter(pkg), highest_folder(pkg)
        if folder_max > counter:
            drift.append(f"{pkg.name}: {folder_name} is above last-number.md ({counter:04d})")
        for state in ("active", "archive"):
            d = changes_dir(pkg) / state
            if not d.is_dir():
                continue
            for entry in sorted(d.iterdir()):
                m = NUM.match(entry.name)
                if not m or not entry.is_dir():
                    continue
                doc, progress = entry / "RFC.md", entry / "tasks.md"
                days = read_row(progress, "Days worked") or ""
                phase = read_row(doc, "Phase") or "?"
                rows.append(
                    [
                        f"{identifier}/{m.group(1)}",
                        entry.name[len(m.group(1)) + 1 :] or "—",
                        f"{phase} (archived)" if state == "archive" else phase,
                        cell(read_row(progress, "Estimate")),
                        cell(read_row(progress, "Started")),
                        cell(read_row(progress, "Completed")),
                        str(len(re.findall(r"\d{4}-\d{2}-\d{2}", days))),
                    ]
                )
    headings = ["RFC", "Slug", "Phase", "Est", "Started", "Completed", "Days"]
    print("| " + " | ".join(headings) + " |")
    print("| " + " | ".join("---" for _ in headings) + " |")
    for row in rows:
        print("| " + " | ".join(row) + " |")
    if not rows:
        print("| (none) |" + " |" * (len(headings) - 1))
    if drift:
        print("\nNumbering drift:")
        for line in drift:
            print(f"  {line}")
        print("  fix with: rfc.py check --fix")
    return 1 if drift and args.strict else 0


def cmd_check(args, root: Path) -> int:
    packages = [resolve_package(Path(args.package).resolve(), root)] if args.package else all_packages(root)
    bad = unnumbered = 0
    for pkg in packages:
        counter, (folder_max, folder_name) = read_counter(pkg), highest_folder(pkg)
        if folder_max > counter:
            if args.fix:
                counter_file(pkg).write_text(f"{folder_max:04d}\n")
                print(f"{pkg.name}: raised last-number.md {counter:04d} -> {folder_max:04d}")
            else:
                bad += 1
                print(f"{pkg.name}: {folder_name} is above last-number.md ({counter:04d})")
        elif args.verbose:
            print(f"{pkg.name}: ok — last-number.md {counter:04d}, highest folder {folder_max:04d}")
        for name in malformed_folders(pkg):
            unnumbered += 1
            print(f"{pkg.name}: {name} does not start with a four-digit number — numbering and status skip it")
    if bad:
        print("\nRepair with `rfc.py check --fix`; it only ever raises last-number.md.")
    if unnumbered:
        print("\nOne RFC, one number: `NNNN-slug`, never `0001a`/`0001b`. --fix will not rename a folder —")
        print("the RFC ID inside it has to move with it. Allocate a real number with `rfc.py new` and rename.")
    return 1 if bad or unnumbered else 0


HOOK_MESSAGE = """Blocked: {path}

RFC {n:04d} was never allocated — {pkg_path}/specs/changes/last-number.md says {counter:04d}.

Allocate properly:  python3 {script_arg} new "<slug>" --package {pkg_arg}
and write into the folder it creates. If {n:04d} is legitimate history, raise the counter with:
  python3 {script_arg} check --fix --package {pkg_arg}

See spec-driven/reference/repository-layout.md."""

UNNUMBERED_MESSAGE = """Blocked: {path}

`{folder}` is not an RFC folder name. A change folder is `NNNN-slug`: four digits, a hyphen, the slug.
Nothing here can read a number out of that name, so the folder would allocate nothing and stay unguarded.
One RFC, one number — `0001a` and `0001b` are two changes, so two numbers.

  python3 {script_arg} new "<slug>" --package {pkg_arg}

Then write into the folder that creates.

See spec-driven/reference/repository-layout.md."""


PATCH_WRITE_HEADERS = ("*** Add File: ", "*** Update File: ", "*** Move to: ")


def hook_paths(payload: dict) -> list[Path]:
    """Extract write targets from Claude Write/Edit or Codex apply_patch hook payloads."""
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, (dict, str)):
        tool_input = payload.get("input", {})

    cwd = Path(payload.get("cwd") or Path.cwd())

    def resolve(raw: str) -> Path:
        path = Path(raw)
        return path.resolve() if path.is_absolute() else (cwd / path).resolve()

    if isinstance(tool_input, dict):
        file_path = tool_input.get("file_path")
        if isinstance(file_path, str) and file_path:
            return [resolve(file_path)]
        command = tool_input.get("command") or tool_input.get("patch")
    else:
        command = tool_input

    if payload.get("tool_name") != "apply_patch" or not isinstance(command, str):
        return []

    paths: list[Path] = []
    for line in command.splitlines():
        for header in PATCH_WRITE_HEADERS:
            if line.startswith(header):
                paths.append(resolve(line.removeprefix(header)))
                break
    return paths


def check_hook_path(path: Path, root: Path) -> int:
    """Reject one write target when its active RFC folder was not allocated."""
    if not path.is_relative_to(root):
        return 0
    parts = path.relative_to(root).parts
    i = max(
        (k for k in range(len(parts) - 2) if parts[k : k + 3] == ("specs", "changes", "active")),
        default=None,
    )
    if i is None or len(parts) <= i + 3:
        return 0
    folder = parts[i + 3]
    m = NUM.match(folder)
    rel_pkg = Path(*parts[:i]) if i else Path(".")
    package = (root / rel_pkg).resolve()
    script_arg = shlex.quote(str(Path(__file__).resolve()))
    package_arg = shlex.quote(str(package))
    if not m:
        # Only when the write is *inside* the folder: a file sitting directly in `active/` is not a
        # change folder and is none of our business.
        if len(parts) > i + 4:
            print(
                UNNUMBERED_MESSAGE.format(
                    path=path.relative_to(root),
                    folder=folder,
                    script_arg=script_arg,
                    pkg_arg=package_arg,
                ),
                file=sys.stderr,
            )
            return 2
        return 0
    n, counter = int(m.group(1)), read_counter(package)
    if n <= counter:
        return 0
    print(
        HOOK_MESSAGE.format(
            path=path.relative_to(root),
            n=n,
            counter=counter,
            pkg_path=package,
            script_arg=script_arg,
            pkg_arg=package_arg,
        ),
        file=sys.stderr,
    )
    return 2


def cmd_hook(args, root: Path) -> int:
    """PreToolUse guard. Fails open on anything unexpected: a broken guard must not block work."""
    try:
        payload = json.load(sys.stdin)
        for path in hook_paths(payload):
            result = check_hook_path(path, root)
            if result:
                return result
        return 0
    except Exception:
        return 0


# ---------------------------------------------------------------------------- cli


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="rfc.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    def with_package(sp):
        sp.add_argument("--package", help="package directory (default: nearest one holding specs/changes/)")
        return sp

    sp = with_package(sub.add_parser("new", help="allocate the next number and scaffold the folder"))
    sp.add_argument("slug", help="short kebab-case slug for the folder name")
    sp.add_argument("--no-templates", action="store_true", help="create the folder empty")

    sp = with_package(sub.add_parser("day", help="stamp Started and today's date into the Progress block"))
    sp.add_argument("rfc", help="number, folder name, RFC ID, or path")
    sp.add_argument("--date", help="ISO date to record instead of today")

    sp = with_package(sub.add_parser("status", help="every RFC with its phase, estimate, and dates"))
    sp.add_argument("--strict", action="store_true", help="exit non-zero if numbering has drifted")

    sp = with_package(sub.add_parser("check", help="verify numbering and folder names"))
    sp.add_argument("--fix", action="store_true", help="raise last-number.md to the highest folder")
    sp.add_argument("--verbose", action="store_true")

    sub.add_parser("hook", help="PreToolUse guard; reads the tool payload on stdin")

    args = p.parse_args(argv)
    start = Path(args.package).resolve() if getattr(args, "package", None) else Path.cwd()
    try:
        root = git_root(start if start.exists() else Path.cwd())
    except Abort as e:
        if args.command == "hook":
            return 0
        print(f"error: {e}", file=sys.stderr)
        return 1
    handlers = {
        "new": cmd_new,
        "day": cmd_day,
        "status": cmd_status,
        "check": cmd_check,
        "hook": cmd_hook,
    }
    try:
        return handlers[args.command](args, root)
    except Abort as e:
        print(f"{e}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
