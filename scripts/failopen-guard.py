#!/usr/bin/env python3
"""Fail-open gate for the hook layer (app/src/main/kotlin/**/xposed/**). Fails (exit 1) on any finding.

A change in a hooked app or in the system must only ever make a protection stop working, never
break the host. This checks the patterns that guarantee it, statically:

1. Every hook registration (`xposed.hook(...)` / `hookClassInitializer(...)`) chains
   `.setExceptionMode(ExceptionMode.PROTECTIVE)`, or `.setExceptionMode(ExceptionMode.PASSTHROUGH)`
   with a `FailOpenHooker` as the hooker (passed directly or through a `val` bound to one), the
   only way a deliberate exception may reach the caller. The mode must be a literal: a parameter
   would hide what the call sites pass.
2. Every hooker guards its own logic with a `Throwable` catch: each class or object implementing
   `Hooker`, each `Hooker { ... }` lambda, each `.intercept { ... }` lambda and each lambda given to
   `hooks.hook(...)` must contain `catch (... : Throwable)` or call an approved guard (`safely`,
   `guarded`, `.guard(`) or call a function of the same file that does (one level). `FailOpenHooker`
   satisfies this by construction.
3. No eager class initialisation: `Class.forName(name, true, ...)` runs an app's `<clinit>` early.
4. Every `HandlerThread(` is in a file that sets `uncaughtExceptionHandler` (system_server would
   otherwise die with the thread): use `ModuleThreads.handler`.

To satisfy it, route new hooks through FailOpenHooker (or a hooker class that catches Throwable around
everything except `chain.proceed`) and set the exception mode explicitly.

Usage: scripts/failopen-guard.py   (run from anywhere inside the repository; stdlib only)
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

HOOK_ROOT = "app/src/main/kotlin/io/github/hyperprivacy/xposed"
HOOK_CALL = re.compile(r"\bxposed\.(hook|hookClassInitializer)\(")
GUARD_CALL = re.compile(r"\b(safely|guarded)\s*[({]|\.guard\s*\(|\bguard\s*\(")
THROWABLE_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(java\.lang\.)?Throwable\s*\)")
HOOKER_CLASS = re.compile(r"\b(class|object)\s+(\w+)?[^{;=]*?:\s*[^{;=]*\bHooker\b")
HOOKER_SAM = re.compile(r"\bHooker\s*\{")
HELPER_LAMBDA = re.compile(r"\bhooks\.hook\(")
EAGER_INIT = re.compile(r"Class\.forName\([^()]*,\s*true\s*,")
FAIL_OPEN_VAL = re.compile(r"\bva[lr]\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?=\s*FailOpenHooker\(")


def repo_root() -> Path:
    out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
    return Path(out.stdout.strip())


def sanitise(text: str) -> str:
    """Blanks comments and string literals (newlines kept), so brackets and keywords in them never count."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(re.sub(r"[^\n]", " ", text[i:end]))
            i = end
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append('"' + re.sub(r"[^\n]", " ", text[i + 1:end - 1]) + '"')
            i = end
        elif text[i] in "\"'":
            quote, j = text[i], i + 1
            while j < n and text[j] != quote and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            end = min(j + 1, n)
            out.append(quote + " " * (end - i - 2) + quote)
            i = end
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def closing(text: str, start: int) -> int:
    """Index just past the bracket matching the one at [start]."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    stack = [pairs[text[start]]]
    i = start + 1
    while i < len(text) and stack:
        c = text[i]
        if c in pairs:
            stack.append(pairs[c])
        elif stack and c == stack[-1]:
            stack.pop()
        i += 1
    return i


def skip_space(text: str, i: int) -> int:
    while i < len(text) and text[i].isspace():
        i += 1
    return i


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def function_bodies(text: str) -> dict[str, list[str]]:
    """Body text of every function of the file, by name (overloads collected)."""
    bodies: dict[str, list[str]] = {}
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", text):
        params_end = closing(text, m.end() - 1)
        j = params_end
        while j < len(text) and text[j] not in "{=\n":
            j += 1
        j = skip_space(text, j)
        if j < len(text) and text[j] == "{":
            body = text[j:closing(text, j)]
        elif j < len(text) and text[j] == "=":
            nl = text.find("\nfun", j)
            body = text[j:j + 2000 if nl < 0 else nl]
        else:
            continue
        bodies.setdefault(m.group(1), []).append(body)
    return bodies


def guards_itself(body: str, bodies: dict[str, list[str]], depth: int = 0) -> bool:
    """[body] catches Throwable, calls an approved guard, or delegates to a function of the file that does."""
    if THROWABLE_CATCH.search(body) or GUARD_CALL.search(body) or "FailOpenHooker" in body:
        return True
    if depth > 1:
        return False
    for name in re.findall(r"\b(\w+)\s*\(", body):
        if any(guards_itself(b, bodies, depth + 1) for b in bodies.get(name, []) if b is not body):
            return True
    return False


def hook_chain(text: str, call: re.Match) -> tuple[str | None, str | None, int]:
    """(exception mode argument, intercept argument, end) of the builder chain starting at [call]."""
    i = closing(text, call.end() - 1)
    mode = None
    while True:
        j = skip_space(text, i)
        m = re.match(r"\.(\w+)\s*", text[j:])
        if not m:
            return mode, None, j
        k = j + m.end()
        if k < len(text) and text[k] in "({":
            end = closing(text, k)
            arg = text[k + 1:end - 1].strip()
            if m.group(1) == "setExceptionMode":
                mode = arg
            elif m.group(1) == "intercept":
                return mode, text[k:end], end
            i = end
        else:
            return mode, None, k


def check_file(rel: str, text: str) -> list[str]:
    problems: list[str] = []
    bodies = function_bodies(text)
    fail_open_vals = set(FAIL_OPEN_VAL.findall(text))

    for call in HOOK_CALL.finditer(text):
        where = f"{rel}:{line_of(text, call.start())}"
        mode, hooker, _ = hook_chain(text, call)
        if mode not in ("ExceptionMode.PROTECTIVE", "ExceptionMode.PASSTHROUGH"):
            problems.append(f"{where}: hook without a literal .setExceptionMode(ExceptionMode.PROTECTIVE) "
                            f"(found: {mode or 'none'})")
            continue
        if hooker is None:
            problems.append(f"{where}: hook builder without .intercept(...) in the same chain")
            continue
        if mode == "ExceptionMode.PASSTHROUGH":
            names = set(re.findall(r"\b\w+\b", hooker))
            if "FailOpenHooker" not in hooker and not names & fail_open_vals:
                problems.append(f"{where}: PASSTHROUGH hook must be handled by a FailOpenHooker")
        if hooker.startswith("{") and not guards_itself(hooker, bodies):
            problems.append(f"{where}: intercept lambda does not catch Throwable around its own logic")

    for m in HOOKER_CLASS.finditer(text):
        brace = text.find("{", m.end())
        if brace < 0:
            continue
        body = text[brace:closing(text, brace)]
        intercept = re.search(r"override\s+fun\s+intercept\s*\(", body)
        if intercept is None:
            continue
        name = m.group(2) or "object"
        if name == "FailOpenHooker":
            continue
        rest = body[intercept.start():]
        method_bodies = function_bodies(rest).get("intercept", [rest])
        class_bodies = {**bodies, **function_bodies(body)}
        if not guards_itself(method_bodies[0], class_bodies):
            problems.append(f"{rel}:{line_of(text, m.start())}: Hooker {name}.intercept does not catch Throwable "
                            "around its own logic")

    for pattern, what in ((HOOKER_SAM, "Hooker lambda"), (HELPER_LAMBDA, "hooks.hook lambda")):
        for m in pattern.finditer(text):
            before = text[:m.start()].rstrip()
            if pattern is HOOKER_SAM and (before.endswith(":") or before.endswith(",")):
                continue  # a supertype list (`: Hooker {`), checked as a class above
            start = m.end() - 1
            if text[start] == "(":
                after = skip_space(text, closing(text, start))
                if after >= len(text) or text[after] != "{":
                    continue
                start = after
            body = text[start:closing(text, start)]
            if not guards_itself(body, bodies):
                problems.append(f"{rel}:{line_of(text, m.start())}: {what} does not catch Throwable around its own "
                                "logic")

    for m in EAGER_INIT.finditer(text):
        problems.append(f"{rel}:{line_of(text, m.start())}: Class.forName(..., true, ...) runs <clinit> eagerly")

    if "HandlerThread(" in text and "uncaughtExceptionHandler" not in text:
        line = line_of(text, text.index("HandlerThread("))
        problems.append(f"{rel}:{line}: HandlerThread without uncaughtExceptionHandler; use ModuleThreads.handler")
    return problems


def main() -> int:
    root = repo_root()
    files = sorted(p for p in (root / HOOK_ROOT).rglob("*.kt"))
    if not files:
        print(f"failopen-guard: no sources under {HOOK_ROOT}", file=sys.stderr)
        return 1
    problems: list[str] = []
    for path in files:
        problems += check_file(path.relative_to(root).as_posix(), sanitise(path.read_text(encoding="utf-8")))
    for problem in problems:
        print(problem)
    if problems:
        print(f"failopen-guard: {len(problems)} finding(s); see the header of scripts/failopen-guard.py",
              file=sys.stderr)
        return 1
    print(f"failopen-guard: {len(files)} files OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
