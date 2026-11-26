#!/usr/bin/env python3
"""Code-smell guards that detekt does not cover. Fails (exit 1) on any finding.

1. Every suppression (@Suppress, @file:Suppress, @SuppressLint) states why: a `//` comment on the
   same line or a comment directly above it (line comment, block or KDoc end).
2. No duplicated production code: a run of WINDOW consecutive normalised source lines that occurs
   twice (in one file or across files) is reported. Generated files and tests are skipped.

Usage: scripts/smell-guard.py   (run from anywhere inside the repository; stdlib only)
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

WINDOW = 12
SOURCE_GLOBS = ("app/src/**/*.kt", "core/src/**/*.kt")
GENERATED = {
    "core/src/main/kotlin/io/github/hyperprivacy/core/tracker/TrackerData.kt",
    "core/src/main/kotlin/io/github/hyperprivacy/core/baseline/BaselineData.kt",
}
SUPPRESSION = re.compile(r"@(file:)?Suppress(Lint)?\(")
TRIVIAL = re.compile(r"^[\s{}()\[\],;]*$|^(import|package)\s|^@\w+$|^\)\s*[:{]?$")


def repo_root() -> Path:
    out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
    return Path(out.stdout.strip())


def sources(root: Path) -> list[Path]:
    found: set[Path] = set()
    for pattern in SOURCE_GLOBS:
        found.update(p for p in root.glob(pattern) if "/build/" not in p.as_posix())
    return sorted(p for p in found if p.relative_to(root).as_posix() not in GENERATED)


def has_reason(lines: list[str], index: int) -> bool:
    if "//" in lines[index]:
        return True
    above = index - 1
    while above >= 0 and lines[above].strip().startswith("@"):
        above -= 1
    if above < 0:
        return False
    prev = lines[above].strip()
    return prev.startswith("//") or prev.endswith("*/")


def check_suppressions(root: Path, files: list[Path]) -> list[str]:
    problems = []
    for path in files:
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if SUPPRESSION.search(line) and not has_reason(lines, i):
                rel = path.relative_to(root).as_posix()
                problems.append(f"{rel}:{i + 1}: suppression without a reason comment: {line.strip()}")
    return problems


def normalise(line: str) -> str:
    line = re.sub(r"//.*$", "", line).strip()
    return re.sub(r"\s+", " ", line)


def check_duplicates(root: Path, files: list[Path]) -> list[str]:
    seen: dict[str, tuple[str, int]] = {}
    problems = []
    for path in files:
        rel = path.relative_to(root).as_posix()
        meaningful: list[tuple[int, str]] = []
        in_block = False
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            text = normalise(raw)
            if in_block:
                in_block = "*/" not in text
                continue
            if text.startswith("/*"):
                in_block = "*/" not in text
                continue
            if text.startswith("*") or not text or TRIVIAL.match(text):
                continue
            meaningful.append((number, text))
        reported_until = -1
        for start in range(len(meaningful) - WINDOW + 1):
            chunk = "\n".join(text for _, text in meaningful[start : start + WINDOW])
            digest = hashlib.sha1(chunk.encode()).hexdigest()
            line_no = meaningful[start][0]
            first = seen.setdefault(digest, (rel, line_no))
            if first != (rel, line_no) and start > reported_until:
                problems.append(f"{rel}:{line_no}: {WINDOW} lines duplicate {first[0]}:{first[1]}")
                reported_until = start + WINDOW - 1
    return problems


def main() -> int:
    root = repo_root()
    files = sources(root)
    # Tests may restate production data on purpose (pinned lists, expected tables).
    main_sources = [p for p in files if "/src/test/" not in p.as_posix()]
    problems = check_suppressions(root, files) + check_duplicates(root, main_sources)
    for problem in problems:
        print(f"::error::{problem}" if "GITHUB_ACTIONS" in os.environ else problem)
    print(f"smell-guard: {len(files)} Kotlin files, {len(problems)} finding(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
