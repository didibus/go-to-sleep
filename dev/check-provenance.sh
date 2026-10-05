#!/bin/bash
# Reject machine-specific provenance from a public Git checkout and optional release packages.
set -euo pipefail

fail() {
  echo "check-provenance.sh: provenance inspection failed" >&2
  exit 1
}

command -v git >/dev/null 2>&1 || fail
command -v python3 >/dev/null 2>&1 || fail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)" || fail
CHECKOUT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" || fail

python3 - "$CHECKOUT" "$@" <<'PY'
import getpass
import base64
import functools
import gzip
import html
import io
import os
import pathlib
import pwd
import re
import secrets
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import unicodedata
import urllib.parse


ZERO_WIDTH_CATEGORIES = {"Cf"}
TEXT_DECODE_ENCODINGS = (
    "utf-8",
    "utf-16-le",
    "utf-16-be",
    "utf-32-le",
    "utf-32-be",
)
BACKSLASH_ESCAPE_PATTERN = re.compile(
    r"\\(?:u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|x[0-9A-Fa-f]{2})"
)
CONTIGUOUS_HEX_PATTERN = re.compile(
    r"(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}){4,}(?![0-9A-Fa-f])"
)
PREFIXED_HEX_PATTERN = re.compile(
    r"(?<![0-9A-Za-z])(?:0x[0-9A-Fa-f]{2}(?:[\s,;:+|_-]*)){4,}"
)
BASE64_PATTERN = re.compile(
    r"(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{8,}={0,2}"
    r"(?![A-Za-z0-9+/_=-])"
)
QUOTED_FRAGMENT_PATTERN = re.compile(
    r"""(?P<quote>["'])(?P<body>(?:\\.|(?!\1).)*?)(?P=quote)"""
)
FRAGMENT_SEPARATOR_PATTERN = re.compile(r"[\s+()]*")
NON_ASCII_WHITESPACE_CODEPOINTS = (
    0x85,
    0xA0,
    0x1680,
    *range(0x2000, 0x200B),
    0x2028,
    0x2029,
    0x202F,
    0x205F,
    0x3000,
)
WIDE_ASCII_UNIT_PATTERNS = {
    "utf-16-le": rb"[\x09-\x0d\x1c-\x7e]\x00",
    "utf-16-be": rb"\x00[\x09-\x0d\x1c-\x7e]",
    "utf-32-le": rb"[\x09-\x0d\x1c-\x7e]\x00\x00\x00",
    "utf-32-be": rb"\x00\x00\x00[\x09-\x0d\x1c-\x7e]",
}


def compile_wide_text_pattern(encoding):
    encoded_whitespace = sorted(
        {
            chr(codepoint).encode(encoding)
            for codepoint in NON_ASCII_WHITESPACE_CODEPOINTS
        }
    )
    ascii_unit = WIDE_ASCII_UNIT_PATTERNS[encoding]
    whitespace_unit = b"(?:" + b"|".join(
        re.escape(value) for value in encoded_whitespace
    ) + b")"
    return re.compile(
        b"(?:" + ascii_unit + b"){4,}"
        + b"(?:(?:" + whitespace_unit + b")+(?:" + ascii_unit + b")+)*"
    )


WIDE_TEXT_PATTERNS = tuple(
    (compile_wide_text_pattern(encoding), encoding)
    for encoding in ("utf-16-le", "utf-16-be", "utf-32-le", "utf-32-be")
)
MAX_DECODE_CANDIDATES = 4096
MAX_DECODE_DEPTH = 8
MAX_DECODE_TEXT = 4 * 1024 * 1024
MAX_DECODE_ROOT_TEXT = 8 * 1024
DECODE_ROOT_OVERLAP = 4 * 1024
MAX_PACKAGE_BYTES = 128 * 1024 * 1024
MAX_CPIO_BYTES = 256 * 1024 * 1024
MAX_PACKAGE_MEMBER_BYTES = 128 * 1024 * 1024
MAX_REPOSITORY_BLOB_BYTES = 16 * 1024 * 1024


class ProvenanceError(Exception):
    pass


def reject(message):
    raise ProvenanceError(message)


def safe_print(message, error=False):
    stream = sys.stderr if error else sys.stdout
    print("check-provenance.sh: {}".format(message), file=stream)


def usage():
    safe_print(
        "usage: dev/check-provenance.sh "
        "[--git-ref REF]... [--release-dir DIR] "
        "[--build-root DIR]... [--canary TOKEN]...",
        error=True,
    )


def parse_arguments(arguments):
    result = {
        "refs": [],
        "release_dir": None,
        "build_roots": [],
        "canaries": [],
    }
    index = 0
    while index < len(arguments):
        option = arguments[index]
        if option in ("--git-ref", "--release-dir", "--build-root", "--canary"):
            if index + 1 >= len(arguments):
                usage()
                reject("invalid arguments")
            value = arguments[index + 1]
            if not value or "\x00" in value or "\n" in value or "\r" in value:
                reject("invalid arguments")
            if option == "--git-ref":
                if value.startswith("-"):
                    reject("invalid Git reference")
                result["refs"].append(value)
            elif option == "--release-dir":
                if result["release_dir"] is not None:
                    reject("duplicate release directory")
                result["release_dir"] = value
            elif option == "--build-root":
                result["build_roots"].append(value)
            else:
                result["canaries"].append(value)
            index += 2
            continue
        if option in ("-h", "--help"):
            usage()
            raise SystemExit(0)
        usage()
        reject("invalid arguments")
    if not result["refs"]:
        result["refs"].append("HEAD")
    return result


def command(argv, cwd=None, input_data=None):
    environment = os.environ.copy()
    environment["LC_ALL"] = "C"
    environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    try:
        completed = subprocess.run(
            argv,
            cwd=cwd,
            env=environment,
            input=input_data,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except (OSError, ValueError):
        reject("required inspection command could not run")
    if completed.returncode != 0:
        reject("required inspection command failed")
    return completed.stdout


def require_tool(name):
    path = shutil.which(name)
    if not path:
        reject("required inspection tool is unavailable")
    return path


def encode_token(value):
    try:
        return os.fsencode(value)
    except (TypeError, UnicodeEncodeError):
        reject("runtime token could not be encoded")


def canonical_text(value):
    normalized = unicodedata.normalize(
        "NFKC", unicodedata.normalize("NFKC", value).casefold()
    )
    return "".join(
        char
        for char in normalized
        if unicodedata.category(char) not in ZERO_WIDTH_CATEGORIES
    )


def compact_fragment_text(value):
    return "".join(char for char in canonical_text(value) if char.isalnum())


def compact_fragment_candidates(value):
    for line in canonical_text(value).splitlines():
        compact = "".join(char for char in line if char.isalnum())
        if compact:
            yield compact


def printable_strings(data, minimum=4):
    current = bytearray()
    for value in data:
        if 32 <= value <= 126 or value in (9, 10, 13):
            current.append(value)
        else:
            if len(current) >= minimum:
                yield bytes(current)
            current.clear()
    if len(current) >= minimum:
        yield bytes(current)


def decode_bytes_as_text(data):
    if len(data) > MAX_DECODE_TEXT:
        return
    for encoding in TEXT_DECODE_ENCODINGS:
        unit = 1
        if encoding.startswith("utf-16"):
            unit = 2
        elif encoding.startswith("utf-32"):
            unit = 4
        if len(data) % unit:
            continue
        try:
            text = data.decode(encoding)
        except UnicodeDecodeError:
            continue
        if text:
            yield text


def decode_backslash_escapes(value):
    if not BACKSLASH_ESCAPE_PATTERN.search(value):
        return ()

    def replacement(match):
        return chr(int(match.group(0)[2:], 16))

    decoded = BACKSLASH_ESCAPE_PATTERN.sub(replacement, value)
    decoded = decoded.encode(
        "utf-16-le", "surrogatepass"
    ).decode("utf-16-le", "replace")
    candidates = [decoded]
    try:
        utf8_decoded = decoded.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        pass
    else:
        if utf8_decoded != decoded:
            candidates.append(utf8_decoded)
    return candidates


def decoded_binary_texts(data):
    return tuple(decode_bytes_as_text(data))


def decoded_quoted_fragments(value):
    matches = list(QUOTED_FRAGMENT_PATTERN.finditer(value))
    if len(matches) < 2:
        return ()
    candidates = []
    run = [matches[0].group("body")]
    previous = matches[0]
    for match in matches[1:]:
        separator = value[previous.end() : match.start()]
        if FRAGMENT_SEPARATOR_PATTERN.fullmatch(separator):
            run.append(match.group("body"))
        else:
            if len(run) > 1:
                candidates.append("".join(run))
            run = [match.group("body")]
        previous = match
    if len(run) > 1:
        candidates.append("".join(run))
    return candidates


def decoded_text_transforms(value):
    transformed = list(decoded_quoted_fragments(value))
    html_value = html.unescape(value)
    if html_value != value:
        transformed.append(html_value)
    if re.search(r"%[0-9A-Fa-f]{2}", value):
        transformed.extend(
            decoded_binary_texts(urllib.parse.unquote_to_bytes(value))
        )
    transformed.extend(decode_backslash_escapes(value))
    for match in CONTIGUOUS_HEX_PATTERN.finditer(value):
        try:
            decoded = bytes.fromhex(match.group(0))
        except ValueError:
            continue
        transformed.extend(decoded_binary_texts(decoded))
    for match in PREFIXED_HEX_PATTERN.finditer(value):
        pairs = re.findall(r"0x([0-9A-Fa-f]{2})", match.group(0))
        if pairs:
            transformed.extend(
                decoded_binary_texts(bytes.fromhex("".join(pairs)))
            )
    for match in BASE64_PATTERN.finditer(value):
        token = match.group(0)
        if len(token) > MAX_DECODE_TEXT:
            continue
        padding = "=" * ((4 - len(token) % 4) % 4)
        for altchars in (None, b"-_"):
            try:
                decoded = base64.b64decode(
                    (token + padding).encode("ascii"),
                    altchars=altchars,
                    validate=True,
                )
            except (ValueError, UnicodeEncodeError):
                continue
            transformed.extend(decoded_binary_texts(decoded))
    return transformed


def bounded_text_roots(value):
    lines = value.splitlines()
    if not lines:
        lines = [value]
    step = MAX_DECODE_ROOT_TEXT - DECODE_ROOT_OVERLAP
    for line in lines:
        if not line:
            continue
        if len(line) <= MAX_DECODE_ROOT_TEXT:
            yield line
            continue
        start = 0
        while start < len(line):
            yield line[start : start + MAX_DECODE_ROOT_TEXT]
            if start + MAX_DECODE_ROOT_TEXT >= len(line):
                break
            start += step


def decoded_root_candidates(initial):
    queue = [(initial, 0)]
    seen = set()
    while queue:
        value, depth = queue.pop(0)
        if value in seen:
            continue
        seen.add(value)
        if len(seen) > MAX_DECODE_CANDIDATES:
            reject("encoded provenance candidate limit exceeded")
        yield value
        if len(value) > MAX_DECODE_TEXT:
            continue
        transformed_values = decoded_text_transforms(value)
        if depth >= MAX_DECODE_DEPTH:
            if any(
                transformed
                and transformed != value
                and transformed not in seen
                for transformed in transformed_values
            ):
                reject("encoded provenance decode depth limit exceeded")
            continue
        for transformed in transformed_values:
            if transformed and transformed not in seen:
                queue.append((transformed, depth + 1))


def decoded_value_candidates(value):
    for fragmented in decoded_quoted_fragments(value):
        yield from decoded_root_candidates(fragmented)
    for root in bounded_text_roots(value):
        yield from decoded_root_candidates(root)


def decoded_text_candidates(data):
    try:
        complete_utf8 = data.decode("utf-8")
    except UnicodeDecodeError:
        complete_utf8 = None
    if complete_utf8 is not None:
        yield from decoded_value_candidates(complete_utf8)
    for decoded in decode_bytes_as_text(data):
        if decoded == complete_utf8:
            continue
        yield from decoded_value_candidates(decoded)
    if complete_utf8 is None:
        for printable in printable_strings(data):
            value = printable.decode("ascii")
            yield from decoded_value_candidates(value)
    if len(data) > MAX_DECODE_TEXT and b"\0" in data:
        for pattern, encoding in WIDE_TEXT_PATTERNS:
            for match in pattern.finditer(data):
                value = match.group(0).decode(encoding)
                yield from decoded_value_candidates(value)


@functools.lru_cache(maxsize=512)
def encoded_token_variants(token):
    try:
        text = os.fsdecode(token)
    except (TypeError, UnicodeDecodeError):
        reject("runtime token could not be decoded")
    values = {
        text,
        unicodedata.normalize("NFKC", text),
        text.lower(),
        text.upper(),
        text.casefold(),
    }
    encoded = set()
    for value in values:
        for encoding in TEXT_DECODE_ENCODINGS:
            encoded.add(value.encode(encoding))
    return encoded


@functools.lru_cache(maxsize=256)
def decoded_surface_views(data):
    canonical_candidates = []
    compact_candidates = []
    for value in decoded_text_candidates(data):
        canonical_candidates.append(canonical_text(value))
        compact_candidates.extend(compact_fragment_candidates(value))
    return tuple(canonical_candidates), tuple(compact_candidates)


def token_match(data, token, boundary_aware=False, views=None):
    if any(candidate in data for candidate in encoded_token_variants(token)):
        return True
    token_text = os.fsdecode(token)
    canonical_token = canonical_text(token_text)
    compact_token = compact_fragment_text(token_text)
    if views is None:
        views = decoded_surface_views(data)
    canonical_candidates, compact_candidates = views
    for canonical_value in canonical_candidates:
        if boundary_aware:
            if re.search(
                r"(?<![a-z0-9]){}(?![a-z0-9])".format(
                    re.escape(canonical_token)
                ),
                canonical_value,
            ):
                return True
        elif canonical_token in canonical_value:
            return True
    if len(compact_token) >= 4:
        for compact_value in compact_candidates:
            if compact_token in compact_value:
                return True
    return False


def contains_absolute_home_path(data, views=None):
    pattern = re.compile(r"(?i)(?:^|[^A-Za-z0-9])/(?:users|home)/[^/\\\s]+")
    if views is None:
        views = decoded_surface_views(data)
    return any(
        pattern.search(value)
        for value in views[0]
    )


class Scanner:
    def __init__(self):
        self.tokens = []
        self._token_keys = set()

    def add_token(self, kind, value):
        if value is None:
            reject("runtime token is unavailable")
        token = encode_token(value)
        if not token or b"\x00" in token or b"\n" in token or b"\r" in token:
            reject("runtime token is invalid")
        key = (kind, token.lower())
        if key not in self._token_keys:
            self._token_keys.add(key)
            self.tokens.append((kind, token))

    def scan(self, data, surface, decode=True):
        if not isinstance(data, bytes):
            reject("inspection received non-byte data")
        for kind, token in self.tokens:
            if kind in ("username", "hostname"):
                continue
            if any(
                candidate in data
                for candidate in encoded_token_variants(token)
            ):
                reject("{} contains a runtime-derived {} token".format(surface, kind))
        raw_home_pattern = re.compile(
            rb"(?i)(?:^|[^A-Za-z0-9])/(?:users|home)/[^/\\\s]+"
        )
        if raw_home_pattern.search(data):
            reject("{} contains an absolute home path".format(surface))
        if not decode:
            return
        profiles = [
            (
                kind,
                canonical_text(os.fsdecode(token)),
                compact_fragment_text(os.fsdecode(token)),
            )
            for kind, token in self.tokens
        ]
        home_pattern = re.compile(
            r"(?i)(?:^|[^A-Za-z0-9])/(?:users|home)/[^/\\\s]+"
        )
        for value in decoded_text_candidates(data):
            canonical_value = canonical_text(value)
            compact_values = tuple(compact_fragment_candidates(value))
            for kind, canonical_token, compact_token in profiles:
                if kind in ("username", "hostname"):
                    matched = re.search(
                        r"(?<!\w){}(?!\w)".format(
                            re.escape(canonical_token)
                        ),
                        canonical_value,
                    )
                else:
                    matched = canonical_token in canonical_value
                if matched or (
                    kind not in ("username", "hostname")
                    and
                    len(compact_token) >= 4
                    and any(
                        compact_token in compact_value
                        for compact_value in compact_values
                    )
                ):
                    reject(
                        "{} contains a runtime-derived {} token".format(
                            surface, kind
                        )
                    )
            if home_pattern.search(canonical_value):
                reject("{} contains an absolute home path".format(surface))


def canonical_checkout(argument):
    checkout = pathlib.Path(argument)
    try:
        if command(
            ["git", "-C", str(checkout), "rev-parse", "--is-bare-repository"]
        ).strip() != b"false":
            reject("checkout is bare")
        reported = command(
            ["git", "-C", str(checkout), "rev-parse", "--show-toplevel"]
        ).rstrip(b"\n")
        if os.path.realpath(os.fsdecode(reported)) != os.path.realpath(str(checkout)):
            reject("checkout root is inconsistent")
    except OSError:
        reject("checkout could not be inspected")
    return pathlib.Path(os.path.realpath(str(checkout)))


def add_runtime_tokens(scanner, checkout, build_roots, canaries):
    usernames = []
    try:
        usernames.extend((pwd.getpwuid(os.getuid()).pw_name, getpass.getuser()))
    except (KeyError, OSError):
        reject("current username is unavailable")
    for username in usernames:
        scanner.add_token("username", username)

    homes = []
    try:
        homes.append(pwd.getpwuid(os.getuid()).pw_dir)
    except (KeyError, OSError):
        reject("current home is unavailable")
    environment_home = os.environ.get("HOME")
    if environment_home:
        homes.append(environment_home)
    for home in homes:
        if not os.path.isabs(home):
            reject("current home is not absolute")
        scanner.add_token("home", os.path.normpath(home))
        scanner.add_token("home", os.path.realpath(home))

    try:
        hostname = socket.gethostname()
    except OSError:
        reject("current hostname is unavailable")
    scanner.add_token("hostname", hostname)
    short_hostname = hostname.split(".", 1)[0]
    if short_hostname != hostname:
        scanner.add_token("hostname", short_hostname)

    scanner.add_token("checkout", str(checkout))
    scanner.add_token("checkout", os.path.realpath(str(checkout)))

    for build_root in build_roots:
        if not os.path.isabs(build_root):
            reject("build root is not absolute")
        scanner.add_token("build-root", os.path.normpath(build_root))
        scanner.add_token("build-root", os.path.realpath(build_root))

    supplied = list(canaries)
    supplied.extend(
        (
            "gts-canary-" + secrets.token_hex(16),
            "gts-canary-" + secrets.token_hex(16),
        )
    )
    for canary in supplied:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{11,127}", canary):
            reject("canary is not a neutral token")
        scanner.add_token("canary", canary)


def inspect_tracked_worktree(scanner, checkout):
    expected = b"dev/check-provenance.sh"
    tracked = command(["git", "-C", str(checkout), "ls-files", "-z", "--cached"])
    paths = tracked.split(b"\x00")
    if paths and paths[-1] == b"":
        paths.pop()
    if not paths or expected not in paths:
        reject("provenance checker is not tracked")

    for path_bytes in paths:
        if not path_bytes:
            reject("tracked path is invalid")
        scanner.scan(path_bytes, "tracked path")
        path = checkout / os.fsdecode(path_bytes)
        try:
            metadata = os.lstat(str(path))
        except OSError:
            reject("tracked worktree entry is missing")
        if stat.S_ISLNK(metadata.st_mode):
            try:
                target = os.readlink(str(path))
            except OSError:
                reject("tracked symlink could not be read")
            scanner.scan(encode_token(target), "tracked symlink")
        elif stat.S_ISREG(metadata.st_mode):
            if metadata.st_size > MAX_REPOSITORY_BLOB_BYTES:
                reject("tracked content exceeds the inspection size limit")
            try:
                scanner.scan(path.read_bytes(), "tracked content")
            except OSError:
                reject("tracked content could not be read")
        else:
            reject("tracked worktree entry has an unsupported type")


def valid_object_id(value):
    return bool(re.fullmatch(rb"(?:[0-9a-f]{40}|[0-9a-f]{64})", value))


def inspect_git(scanner, checkout, references):
    selected = []
    for reference in references:
        scanner.scan(encode_token(reference), "selected reference")
        object_id = command(
            [
                "git",
                "-C",
                str(checkout),
                "rev-parse",
                "--verify",
                reference + "^{object}",
            ]
        ).strip()
        if not valid_object_id(object_id):
            reject("selected reference did not resolve to an object")
        selected.append(object_id.decode("ascii"))

    object_output = command(
        [
            "git",
            "-C",
            str(checkout),
            "rev-list",
            "--objects",
            "--no-object-names",
        ]
        + selected
    )
    object_ids = set(selected)
    for line in object_output.splitlines():
        if not valid_object_id(line):
            reject("reachable object listing is malformed")
        object_ids.add(line.decode("ascii"))

    index_output = command(
        ["git", "-C", str(checkout), "ls-files", "-s", "-z", "--cached"]
    )
    for record in index_output.split(b"\x00"):
        if not record:
            continue
        try:
            header, path = record.split(b"\t", 1)
            mode, object_id, stage = header.split(b" ")
        except ValueError:
            reject("Git index entry is malformed")
        if mode not in (b"100644", b"100755", b"120000") or stage != b"0":
            reject("Git index entry has an unsupported mode or stage")
        if not valid_object_id(object_id):
            reject("Git index object identifier is malformed")
        scanner.scan(path, "Git index path")
        object_ids.add(object_id.decode("ascii"))

    for object_id in sorted(object_ids):
        object_type = command(
            ["git", "-C", str(checkout), "cat-file", "-t", object_id]
        ).strip()
        if object_type not in (b"blob", b"tree", b"commit", b"tag"):
            reject("reachable Git object has an unsupported type")
        size_raw = command(
            ["git", "-C", str(checkout), "cat-file", "-s", object_id]
        ).strip()
        try:
            object_size = int(size_raw.decode("ascii"))
        except (ValueError, UnicodeDecodeError):
            reject("reachable Git object size is invalid")
        if object_size > MAX_REPOSITORY_BLOB_BYTES:
            reject("reachable Git object exceeds the inspection size limit")
        body = command(
            [
                "git",
                "-C",
                str(checkout),
                "cat-file",
                object_type.decode("ascii"),
                object_id,
            ]
        )
        scanner.scan(body, "reachable Git object")

    scanner.scan(
        command(["git", "-C", str(checkout), "config", "--local", "--null", "--list"]),
        "local Git metadata",
    )
    scanner.scan(
        command(
            [
                "git",
                "-C",
                str(checkout),
                "for-each-ref",
                "--format=%(refname)%00%(objectname)",
            ]
        ),
        "Git reference metadata",
    )
    symbolic_head = subprocess.run(
        ["git", "-C", str(checkout), "symbolic-ref", "-q", "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if symbolic_head.returncode not in (0, 1):
        reject("HEAD metadata could not be inspected")
    scanner.scan(symbolic_head.stdout, "HEAD metadata")


def safe_archive_names(data):
    if b"\x00" in data:
        reject("archive member listing contains a null byte")
    names = data.splitlines()
    if not names:
        reject("archive member listing is empty")
    for name in names:
        if not name:
            reject("archive member name is empty")
        normalized = name.replace(b"\\", b"/")
        if normalized.startswith(b"/"):
            reject("archive member path is absolute")
        parts = normalized.split(b"/")
        if any(part in (b"", b"..") for part in parts):
            reject("archive member path is unsafe")
    return names


def scan_strings(scanner, path, surface, strings_tool, decode=True):
    output = command([strings_tool, "-a", str(path)])
    scanner.scan(output, surface, decode=decode)


def scan_tree(
    scanner,
    directory,
    surface,
    strings_tool=None,
    shallow_names=(),
):
    try:
        entries = sorted(directory.rglob("*"), key=lambda item: os.fsencode(str(item)))
    except OSError:
        reject("extracted package tree could not be listed")
    for entry in entries:
        try:
            relative = entry.relative_to(directory)
            scanner.scan(os.fsencode(str(relative)), surface + " path")
            metadata = os.lstat(str(entry))
        except (OSError, ValueError):
            reject("extracted package entry could not be inspected")
        if stat.S_ISDIR(metadata.st_mode):
            continue
        if stat.S_ISLNK(metadata.st_mode):
            reject("extracted package tree contains a symlink")
        if not stat.S_ISREG(metadata.st_mode):
            reject("extracted package entry has an unsupported type")
        if metadata.st_size > MAX_PACKAGE_MEMBER_BYTES:
            reject("extracted package entry exceeds the inspection size limit")
        decode = entry.name not in shallow_names
        try:
            scanner.scan(
                entry.read_bytes(),
                surface + " content",
                decode=decode,
            )
        except OSError:
            reject("extracted package content could not be read")
        if strings_tool is not None:
            scan_strings(
                scanner,
                entry,
                surface + " binary strings",
                strings_tool,
                decode=decode,
            )


def parse_octal_field(value):
    try:
        text = value.decode("ascii")
    except UnicodeDecodeError:
        reject("CPIO numeric field is not ASCII")
    if not text or not re.fullmatch(r"[0-7]+", text):
        reject("CPIO numeric field is malformed")
    return int(text, 8)


def canonical_cpio_member_name(name_bytes):
    try:
        name = name_bytes.decode("utf-8")
    except UnicodeDecodeError:
        reject("CPIO member name is not UTF-8")
    if (
        not name
        or name.startswith("/")
        or "\\" in name
        or any(ord(char) < 32 or ord(char) == 127 for char in name)
    ):
        reject("CPIO member path is unsafe")
    if name == ".":
        return name
    normalized = name[2:] if name.startswith("./") else name
    parts = normalized.split("/")
    if (
        not normalized
        or normalized.startswith("./")
        or any(part in ("", ".", "..") for part in parts)
        or "/".join(parts) != normalized
    ):
        reject("CPIO member path is non-canonical")
    return normalized


def cpio_collision_keys(path):
    return {
        path,
        path.casefold(),
        unicodedata.normalize("NFC", path),
        unicodedata.normalize("NFD", path),
        canonical_text(path),
    }


def validate_cpio_archive(scanner, data, surface):
    if len(data) > MAX_CPIO_BYTES:
        reject("CPIO archive exceeds the inspection size limit")
    offset = 0
    saw_trailer = False
    seen_path_keys = {}
    seen_member_inodes = set()
    while offset < len(data):
        if len(data) - offset < 76:
            reject("CPIO header is truncated")
        header = data[offset : offset + 76]
        offset += 76
        if header[:6] != b"070707":
            reject("CPIO format is unsupported")
        device = parse_octal_field(header[6:12])
        inode = parse_octal_field(header[12:18])
        mode = parse_octal_field(header[18:24])
        uid = parse_octal_field(header[24:30])
        gid = parse_octal_field(header[30:36])
        link_count = parse_octal_field(header[36:42])
        rdev = parse_octal_field(header[42:48])
        mtime = parse_octal_field(header[48:59])
        name_size = parse_octal_field(header[59:65])
        file_size = parse_octal_field(header[65:76])
        if file_size > MAX_PACKAGE_MEMBER_BYTES:
            reject("CPIO member exceeds the inspection size limit")
        if name_size < 1 or offset + name_size > len(data):
            reject("CPIO member name is truncated")
        name_field = data[offset : offset + name_size]
        offset += name_size
        if not name_field.endswith(b"\0") or b"\0" in name_field[:-1]:
            reject("CPIO member name is malformed")
        name_bytes = name_field[:-1]
        if name_bytes == b"TRAILER!!!":
            if (
                mode != 0
                or uid != 0
                or gid != 0
                or link_count != 1
                or rdev != 0
                or mtime != 0
                or name_size != 11
                or file_size != 0
            ):
                reject("CPIO trailer metadata is not canonical")
            saw_trailer = True
            break
        normalized = canonical_cpio_member_name(name_bytes)
        for key in cpio_collision_keys(normalized):
            if key in seen_path_keys:
                reject("CPIO contains duplicate or colliding member paths")
            seen_path_keys[key] = normalized
        file_type = stat.S_IFMT(mode)
        if file_type not in (stat.S_IFDIR, stat.S_IFREG):
            reject("CPIO member type is unsupported")
        if uid != 0 or gid != 0:
            reject("CPIO member ownership is not root:wheel")
        if rdev != 0:
            reject("CPIO member device metadata is invalid")
        if link_count < 1:
            reject("CPIO member link count is invalid")
        inode_key = (device, inode)
        if inode_key in seen_member_inodes:
            reject("CPIO member inode is reused")
        seen_member_inodes.add(inode_key)
        if file_type == stat.S_IFDIR:
            if file_size != 0:
                reject("CPIO directory has a body")
        else:
            if link_count != 1:
                reject("CPIO regular file is hard-linked")
        scanner.scan(name_bytes, surface + " member name")
        if offset + file_size > len(data):
            reject("CPIO member body is truncated")
        body = data[offset : offset + file_size]
        scanner.scan(body, surface + " member body")
        offset += file_size
    if not saw_trailer:
        reject("CPIO trailer is missing")
    padding_size = (-offset) % 512
    if len(data) != offset + padding_size:
        reject("CPIO padding length is invalid")
    if data[offset:] != b"\0" * padding_size:
        reject("CPIO padding is not canonical")


def cpio_bytes(raw, gzip_tool):
    if raw.startswith(b"\x1f\x8b"):
        chunks = []
        total = 0
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_CPIO_BYTES:
                        reject("CPIO archive exceeds the decompression size limit")
                    chunks.append(chunk)
        except (OSError, EOFError):
            reject("package member archive decompression failed")
        return b"".join(chunks)
    if raw.startswith(b"070707"):
        return raw
    reject("package member archive has an unsupported encoding")


def inspect_cpio(
    scanner, archive_path, temporary, index, cpio_tool, gzip_tool, strings_tool
):
    try:
        metadata = os.lstat(str(archive_path))
        if metadata.st_size > MAX_PACKAGE_BYTES:
            reject("package member archive exceeds the inspection size limit")
        raw = archive_path.read_bytes()
    except OSError:
        reject("package member archive could not be read")
    archive = cpio_bytes(raw, gzip_tool)
    validate_cpio_archive(scanner, archive, "CPIO")
    scanner.scan(archive, "CPIO archive bytes", decode=False)
    names = command([cpio_tool, "-it"], input_data=archive)
    safe_archive_names(names)
    scanner.scan(names, "CPIO member names")
    metadata = command(
        [cpio_tool, "--numeric-uid-gid", "-itv"], input_data=archive
    )
    scanner.scan(metadata, "CPIO archive metadata")

    destination = temporary / ("cpio-{}".format(index))
    destination.mkdir()
    environment = os.environ.copy()
    environment["COPYFILE_DISABLE"] = "1"
    environment["LC_ALL"] = "C"
    try:
        completed = subprocess.run(
            [cpio_tool, "-idm", "--quiet"],
            cwd=str(destination),
            env=environment,
            input=archive,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except OSError:
        reject("CPIO extraction could not run")
    if completed.returncode != 0:
        reject("CPIO extraction failed")
    scan_tree(scanner, destination, "extracted CPIO", strings_tool)


def inspect_package(scanner, package, tools):
    scanner.scan(os.fsencode(package.name), "release package name")
    try:
        package_metadata = os.lstat(str(package))
        if package_metadata.st_size > MAX_PACKAGE_BYTES:
            reject("release package exceeds the inspection size limit")
        scanner.scan(
            package.read_bytes(),
            "raw release package",
            decode=False,
        )
    except OSError:
        reject("release package could not be read")
    with tempfile.TemporaryDirectory(prefix="gts-provenance-package-") as temporary_name:
        temporary = pathlib.Path(temporary_name)
        toc = temporary / "toc.xml"
        command(
            [tools["xar"], "--dump-toc={}".format(toc), "-f", str(package)]
        )
        try:
            scanner.scan(toc.read_bytes(), "XAR archive metadata")
        except OSError:
            reject("XAR metadata could not be read")
        member_names = command([tools["xar"], "-tf", str(package)])
        names = safe_archive_names(member_names)
        scanner.scan(member_names, "XAR member names")
        for required in (b"Payload", b"Scripts"):
            if names.count(required) != 1:
                reject("release package archive inventory is incomplete")

        outer = temporary / "outer"
        outer.mkdir()
        command([tools["xar"], "-xf", str(package)], cwd=str(outer))
        archives = []
        for name in ("Payload", "Scripts"):
            archive = outer / name
            try:
                metadata = os.lstat(str(archive))
            except OSError:
                reject("package member archive is missing")
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
                reject("package member archive is not a regular file")
            archives.append(archive)
        for index, archive in enumerate(archives):
            inspect_cpio(
                scanner,
                archive,
                temporary,
                index,
                tools["cpio"],
                tools["gzip"],
                tools["strings"],
            )
        scan_tree(
            scanner,
            outer,
            "extracted XAR",
            tools["strings"],
            shallow_names={"Payload", "Scripts"},
        )

        expanded = temporary / "expanded"
        command([tools["pkgutil"], "--expand", str(package), str(expanded)])
        scan_tree(
            scanner,
            expanded,
            "expanded package",
            tools["strings"],
            shallow_names={"Payload", "Scripts"},
        )


def inspect_release(scanner, release_argument):
    release_dir = pathlib.Path(release_argument)
    try:
        metadata = os.lstat(str(release_dir))
    except OSError:
        reject("release directory is unavailable")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        reject("release directory is not a real directory")
    release_dir = pathlib.Path(os.path.realpath(str(release_dir)))

    tools = {
        "xar": require_tool("xar"),
        "pkgutil": require_tool("pkgutil"),
        "cpio": require_tool("cpio"),
        "gzip": require_tool("gzip"),
        "strings": require_tool("strings"),
    }
    packages = []
    try:
        for candidate in release_dir.iterdir():
            if candidate.is_file() and not candidate.is_symlink() and candidate.suffix == ".pkg":
                packages.append(candidate)
    except OSError:
        reject("release directory could not be listed")
    if not packages:
        reject("release directory contains no package")
    scan_tree(
        scanner,
        release_dir,
        "release directory",
        shallow_names={package.name for package in packages},
    )
    for package in sorted(packages, key=lambda item: os.fsencode(item.name)):
        inspect_package(scanner, package, tools)


def main():
    if len(sys.argv) < 2:
        reject("checkout argument is missing")
    checkout = canonical_checkout(sys.argv[1])
    arguments = parse_arguments(sys.argv[2:])
    scanner = Scanner()
    add_runtime_tokens(
        scanner, checkout, arguments["build_roots"], arguments["canaries"]
    )
    inspect_tracked_worktree(scanner, checkout)
    inspect_git(scanner, checkout, arguments["refs"])
    if arguments["release_dir"] is not None:
        inspect_release(scanner, arguments["release_dir"])
    safe_print("ok")


try:
    main()
except ProvenanceError as error:
    safe_print("FAIL: {}".format(str(error)), error=True)
    raise SystemExit(1)
except SystemExit:
    raise
except BaseException:
    safe_print("FAIL: unexpected inspection error", error=True)
    raise SystemExit(1)
PY
