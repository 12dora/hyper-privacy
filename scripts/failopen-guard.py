#!/usr/bin/env python3
"""Fail-open gate for the hook layer (app/src/main/kotlin/**/xposed/**). Fails (exit 1) on any finding.

A change in a hooked app or in the system must only ever make a protection stop working, never
break the host. This checks the patterns that guarantee it, statically:

1. Every hook builder (`<any receiver>.hook(...)` / `hookClassInitializer(...)` followed by
   `.intercept(...)`) chains a literal `.setExceptionMode(ExceptionMode.PROTECTIVE)`, or a literal
   `.setExceptionMode(ExceptionMode.PASSTHROUGH)` whose hooker is a `FailOpenHooker` (constructed
   in place or a `val` bound to one), the only way a deliberate exception may reach the caller. A
   PASSTHROUGH hook may wrap that hooker in a lambda only if everything else the lambda does sits
   inside `try { ... } catch (_: Throwable)`.
2. Every hooker guards its own logic with a `Throwable` catch: each class or object implementing
   `Hooker`, each `Hooker { ... }` lambda, each `.intercept { ... }` lambda and each trailing lambda
   given to a hook helper (`hook(...) { ... }` of any receiver) must contain `catch (... : Throwable)`
   or call an approved guard (`safely`, `guarded`, `.guard(`), or call a function of the same file
   that does (one level). `FailOpenHooker` satisfies this by construction.
3. A fail-open decision never runs the original: the decision given to `FailOpenHooker(...)`,
   `sink(...)` or `rewriteSink(...)` (a lambda, `::name` or `x::name`, and the same-file functions it
   calls, one level) must not call `.proceed(`; `FailOpenHooker` proceeds itself, and a decision that
   proceeds and then fails would run the original twice. Post-call work goes in `Verdict.Transform`.
4. No eager class initialisation: `Class.forName(name, true, ...)` runs an app's `<clinit>` early,
   and so does the one-argument `Class.forName(name)` unless `name` is a string literal or an
   UPPER_CASE constant (platform classes the module names itself).
5. Every `HandlerThread(` is created in a function that sets its `uncaughtExceptionHandler`
   (system_server would otherwise die with the thread): use `ModuleThreads.handler`.
6. No `!!` in the hook layer: a null where one was not expected must fail open, not throw.

To satisfy it, route new hooks through FailOpenHooker (or a hooker class that catches Throwable around
everything except `chain.proceed`) and set the exception mode explicitly.

Usage: scripts/failopen-guard.py   (run from anywhere inside the repository; stdlib only)
Tests: python3 -I scripts/test_failopen_guard.py
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

HOOK_ROOT = "app/src/main/kotlin/io/github/hyperprivacy/xposed"
HOOK_CALL = re.compile(r"(?<![\w.])(?:[\w.]+\.)?(hook|hookClassInitializer)\(")
GUARD_CALL = re.compile(r"\b(safely|guarded)\s*[({]|\.guard\s*\(|\bguard\s*\(")
THROWABLE_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(java\.lang\.)?Throwable\s*\)")
HOOKER_CLASS = re.compile(r"\b(class|object)\s+(\w+)?[^{;=]*?:\s*[^{;=]*\bHooker\b")
HOOKER_SAM = re.compile(r"\bHooker\s*\{")
DECISION_CALL = re.compile(r"(?<![\w])(FailOpenHooker|sink|rewriteSink)\(")
FOR_NAME = re.compile(r"\bClass\.forName\(")
FAIL_OPEN_VAL = re.compile(r"\bva[lr]\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?=\s*FailOpenHooker\(")
NOT_NULL = re.compile(r"!!")
CONSTANT = re.compile(r"^(?:[A-Z][\w]*\.)*[A-Z][A-Z0-9_]*$")


def repo_root() -> Path:
    out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
    return Path(out.stdout.strip())


def sanitise(text: str) -> str:
    """Blanks comments and string literal contents (newlines kept), so brackets and keywords in them never count."""
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


def indent_at(text: str, index: int) -> int:
    start = text.rfind("\n", 0, index) + 1
    return len(text[start:index]) - len(text[start:index].lstrip())


def split_args(inner: str) -> list[str]:
    """Top-level comma-separated arguments of a call's parenthesised text (without the parens)."""
    args, depth, current = [], 0, []
    for c in inner:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(c)
    tail = "".join(current).strip()
    if tail:
        args.append(tail)
    return args


def expression_end(text: str, start: int, fun_indent: int) -> int:
    """End of an expression body starting at [start]: the next line that starts a new declaration."""
    pos = text.find("\n", start)
    while pos >= 0:
        nxt = text.find("\n", pos + 1)
        line = text[pos + 1:len(text) if nxt < 0 else nxt]
        stripped = line.lstrip()
        if stripped:
            indent = len(line) - len(stripped)
            if indent < fun_indent or (indent == fun_indent and (stripped[0].isalnum() or stripped[0] == "@")):
                return pos
        pos = nxt
    return len(text)


def function_bodies(text: str) -> dict[str, list[tuple[int, int]]]:
    """(start, end) of the body of every function of the file, by name (overloads collected)."""
    bodies: dict[str, list[tuple[int, int]]] = {}
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", text):
        j = closing(text, m.end() - 1)
        while j < len(text) and text[j] not in "{=\n":
            j += 1
        j = skip_space(text, j)
        if j < len(text) and text[j] == "{":
            span = (j, closing(text, j))
        elif j < len(text) and text[j] == "=":
            span = (j, expression_end(text, j, indent_at(text, m.start())))
        else:
            continue
        bodies.setdefault(m.group(1), []).append(span)
    return bodies


def calls_in(body: str) -> list[str]:
    return re.findall(r"\b(\w+)\s*[({]", body)


def guards_itself(text: str, body: str, bodies: dict[str, list[tuple[int, int]]], depth: int = 0) -> bool:
    """[body] catches Throwable, calls an approved guard, or calls a function of the file that does."""
    if THROWABLE_CATCH.search(body) or GUARD_CALL.search(body) or "FailOpenHooker" in body:
        return True
    if depth > 0:
        return False
    return any(
        guards_itself(text, text[s:e], bodies, depth + 1)
        for name in calls_in(body)
        for s, e in bodies.get(name, [])
        if text[s:e] != body
    )


def hook_chain(text: str, call: re.Match) -> tuple[bool, str | None, str | None, int]:
    """(is a framework builder, exception mode, intercept argument, end) of the chain starting at [call]."""
    i = closing(text, call.end() - 1)
    mode, builder = None, False
    while True:
        j = skip_space(text, i)
        m = re.match(r"\.(\w+)\s*", text[j:])
        if not m:
            return builder, mode, None, i
        k = j + m.end()
        if k < len(text) and text[k] in "({":
            end = closing(text, k)
            arg = text[k + 1:end - 1].strip()
            if m.group(1) == "setExceptionMode":
                mode, builder = arg, True
            elif m.group(1) == "intercept":
                return True, mode, text[k:end], end
            elif m.group(1) in ("setPriority", "setId"):
                builder = True
            i = end
        else:
            return builder, mode, None, k


def without_guarded_blocks(body: str) -> str:
    """[body] with every `try { ... } catch (...: Throwable) { ... }` removed."""
    out, i = [], 0
    for m in re.finditer(r"\btry\s*\{", body):
        if m.start() < i:
            continue
        end = closing(body, m.end() - 1)
        catch = re.match(r"\s*catch\s*\(\s*\w+\s*:\s*(java\.lang\.)?Throwable\s*\)\s*\{", body[end:])
        if catch is None:
            continue
        out.append(body[i:m.start()])
        i = closing(body, end + catch.end() - 1)
    out.append(body[i:])
    return "".join(out)


def passthrough_lambda_ok(hooker: str, fail_open: set[str]) -> bool:
    """A PASSTHROUGH lambda may only call its FailOpenHooker and return the result; all else guarded."""
    body = without_guarded_blocks(hooker[1:-1])
    body = re.sub(r"^\s*\w+\s*->", "", body)
    allowed = re.compile(r"^(?:val\s+\w+\s*=\s*)?(\w+)\.intercept\(\s*\w+\s*\)$|^\w+$")
    statements = [s.strip() for s in re.split(r"[\n;]", body) if s.strip()]
    for statement in statements:
        m = allowed.match(statement)
        if m is None or (m.group(1) is not None and m.group(1) not in fail_open):
            return False
    return True


def decision_bodies(text: str, arg: str, bodies: dict[str, list[tuple[int, int]]]) -> list[str]:
    """The code a decision argument runs: a lambda, or the functions `::name` / `x::name` names, plus callees."""
    ref = re.fullmatch(r"(?:\w+)?::(\w+)", arg.strip())
    if ref:
        roots = [text[s:e] for s, e in bodies.get(ref.group(1), [])]
    elif arg.strip().startswith("{"):
        roots = [arg]
    else:
        return []
    found = list(roots)
    for root in roots:
        for name in calls_in(root):
            found += [text[s:e] for s, e in bodies.get(name, []) if text[s:e] not in found]
    return found


def check_decisions(rel: str, text: str, bodies: dict[str, list[tuple[int, int]]]) -> list[str]:
    problems = []
    for m in DECISION_CALL.finditer(text):
        if text[max(0, m.start() - 4):m.start()].endswith("fun "):
            continue
        open_paren = m.end() - 1
        end = closing(text, open_paren)
        args = split_args(text[open_paren + 1:end - 1])
        after = skip_space(text, end)
        decision = text[after:closing(text, after)] if after < len(text) and text[after] == "{" else None
        if decision is None and args:
            decision = args[-1]
        if decision is None:
            continue
        if any(".proceed(" in body for body in decision_bodies(text, decision, bodies)):
            problems.append(f"{rel}:{line_of(text, m.start())}: fail-open decision calls chain.proceed "
                            "(use Verdict.Transform for post-call work)")
    return problems


def check_hooks(rel: str, text: str, bodies: dict[str, list[tuple[int, int]]]) -> list[str]:
    problems = []
    fail_open = set(FAIL_OPEN_VAL.findall(text))
    for call in HOOK_CALL.finditer(text):
        if text[max(0, call.start() - 4):call.start()].endswith("fun "):
            continue
        where = f"{rel}:{line_of(text, call.start())}"
        builder, mode, hooker, end = hook_chain(text, call)
        if not builder and hooker is None:
            # A helper of ours (`hooks.hook(x) { ... }`): its trailing lambda is a hooker body.
            after = skip_space(text, end)
            if after < len(text) and text[after] == "{":
                lam = text[after:closing(text, after)]
                if "->" in lam.split("\n", 1)[0] and not guards_itself(text, lam, bodies):
                    problems.append(f"{where}: hook helper lambda does not catch Throwable around its own logic")
            continue
        if mode not in ("ExceptionMode.PROTECTIVE", "ExceptionMode.PASSTHROUGH"):
            problems.append(f"{where}: hook without a literal .setExceptionMode(ExceptionMode.PROTECTIVE) "
                            f"(found: {mode or 'none'})")
            continue
        if hooker is None:
            problems.append(f"{where}: hook builder without .intercept(...) in the same chain")
            continue
        if mode == "ExceptionMode.PASSTHROUGH":
            inner = hooker[1:-1].strip() if hooker.startswith("(") else hooker
            direct = inner.startswith("FailOpenHooker(") or inner in fail_open
            if not direct and not (inner.startswith("{") and passthrough_lambda_ok(inner, fail_open)):
                problems.append(f"{where}: PASSTHROUGH hook must be handled by a FailOpenHooker, with anything "
                                "else inside try/catch(Throwable)")
        if hooker.startswith("{") and not guards_itself(text, hooker, bodies):
            problems.append(f"{where}: intercept lambda does not catch Throwable around its own logic")
    return problems


def check_hookers(rel: str, text: str, bodies: dict[str, list[tuple[int, int]]]) -> list[str]:
    problems = []
    for m in HOOKER_CLASS.finditer(text):
        name = m.group(2) or "object"
        brace = text.find("{", m.end())
        if brace < 0 or name == "FailOpenHooker":
            continue
        class_end = closing(text, brace)
        intercept = re.search(r"override\s+fun\s+intercept\s*\(", text[brace:class_end])
        if intercept is None:
            continue
        spans = [s for s in bodies.get("intercept", []) if brace <= s[0] < class_end]
        body = text[spans[0][0]:spans[0][1]] if spans else text[brace:class_end]
        if not guards_itself(text, body, bodies):
            problems.append(f"{rel}:{line_of(text, m.start())}: Hooker {name}.intercept does not catch Throwable "
                            "around its own logic")
    for m in HOOKER_SAM.finditer(text):
        before = text[:m.start()].rstrip()
        if before.endswith(":") or before.endswith(","):
            continue  # a supertype list (`: Hooker {`), checked as a class above
        start = m.end() - 1
        if not guards_itself(text, text[start:closing(text, start)], bodies):
            problems.append(f"{rel}:{line_of(text, m.start())}: Hooker lambda does not catch Throwable around its "
                            "own logic")
    return problems


def check_misc(rel: str, text: str, bodies: dict[str, list[tuple[int, int]]]) -> list[str]:
    problems = []
    for m in FOR_NAME.finditer(text):
        args = split_args(text[m.end():closing(text, m.end() - 1) - 1])
        eager = (len(args) == 1 and not args[0].startswith('"') and not CONSTANT.match(args[0])) or (
            len(args) == 3 and args[1] == "true")
        if eager:
            problems.append(f"{rel}:{line_of(text, m.start())}: Class.forName initialises the class eagerly; "
                            "use Reflection.loadClass")
    for m in re.finditer(r"\bHandlerThread\(", text):
        enclosing = [(s, e) for spans in bodies.values() for s, e in spans if s <= m.start() < e]
        body = text[slice(*min(enclosing, key=lambda span: span[1] - span[0]))] if enclosing else ""
        if "uncaughtExceptionHandler" not in body:
            problems.append(f"{rel}:{line_of(text, m.start())}: HandlerThread without uncaughtExceptionHandler; "
                            "use ModuleThreads.handler")
    for m in NOT_NULL.finditer(text):
        problems.append(f"{rel}:{line_of(text, m.start())}: `!!` in the hook layer; fail open on null instead")
    return problems


def check_file(rel: str, text: str) -> list[str]:
    bodies = function_bodies(text)
    return (check_hooks(rel, text, bodies) + check_hookers(rel, text, bodies) +
            check_decisions(rel, text, bodies) + check_misc(rel, text, bodies))


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
