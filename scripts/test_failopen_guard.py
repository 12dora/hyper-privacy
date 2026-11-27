#!/usr/bin/env python3
"""Regression tests for scripts/failopen-guard.py: each rule flags its bad pattern and accepts the good one.

Usage: python3 -I scripts/test_failopen_guard.py   (stdlib only)
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
_SPEC = importlib.util.spec_from_file_location("failopen_guard", Path(__file__).with_name("failopen-guard.py"))
guard = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(guard)


def findings(source: str) -> list[str]:
    return guard.check_file("T.kt", guard.sanitise(source))


class ExceptionModeTest(unittest.TestCase):
    def test_missing_mode_is_flagged_whatever_the_receiver(self):
        for receiver in ("xposed.", "env.xposed.", "this.", ""):
            src = f"fun a() {{ {receiver}hook(m).intercept(FailOpenHooker(log, n, ::decide)) }}"
            self.assertTrue(any("literal .setExceptionMode" in f for f in findings(src)), receiver)

    def test_protective_failopen_hook_passes(self):
        src = "fun a() { xposed.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(FailOpenHooker(l, n, ::d)) }"
        self.assertEqual([], findings(src))

    def test_mode_must_be_literal(self):
        src = "fun a(mode: ExceptionMode) { xposed.hook(m).setExceptionMode(mode).intercept(h) }"
        self.assertTrue(findings(src))

    def test_passthrough_needs_failopen(self):
        src = "fun a() { xposed.hook(m).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(MyHooker()) }"
        self.assertTrue(any("PASSTHROUGH" in f for f in findings(src)))

    def test_passthrough_lambda_with_unguarded_logic_is_flagged(self):
        src = """
fun a() {
    val blocker = FailOpenHooker(log, n, ::decide)
    xposed.hook(m).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept { chain ->
        risky(chain)
        blocker.intercept(chain)
    }
}"""
        self.assertTrue(any("PASSTHROUGH" in f for f in findings(src)))

    def test_passthrough_lambda_with_guarded_extra_passes(self):
        src = """
fun a() {
    val blocker = FailOpenHooker(log, n, ::decide)
    xposed.hook(m).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept { chain ->
        val result = blocker.intercept(chain)
        try {
            learn(chain, result)
        } catch (_: Throwable) {
        }
        result
    }
}"""
        self.assertEqual([], findings(src))


class HookerTest(unittest.TestCase):
    def test_unguarded_hooker_class_is_flagged(self):
        src = """
class H : Hooker {
    override fun intercept(chain: Chain): Any? {
        val real = chain.proceed()
        return transform(real)
    }
}"""
        self.assertTrue(any("Hooker H.intercept" in f for f in findings(src)))

    def test_guarded_hooker_class_passes(self):
        src = """
class H : Hooker {
    override fun intercept(chain: Chain): Any? {
        val real = chain.proceed()
        return state.safely { transform(real) } ?: real
    }
}"""
        self.assertEqual([], findings(src))

    def test_helper_lambda_without_receiver_is_checked(self):
        src = """
fun creation() {
    hook(executable) { chain ->
        decide(chain)
        chain.proceed()
    }
}"""
        self.assertTrue(any("helper lambda" in f for f in findings(src)))

    def test_expression_body_does_not_borrow_the_next_functions_catch(self):
        src = """
class H : Hooker {
    override fun intercept(chain: Chain): Any? = helper(chain)

    private fun helper(chain: Chain): Any? = chain.proceed()

    private fun other() {
        try { x() } catch (_: Throwable) { }
    }
}"""
        self.assertTrue(any("Hooker H.intercept" in f for f in findings(src)))


class DecisionTest(unittest.TestCase):
    def test_proceed_inside_a_decision_is_flagged(self):
        for decision in ("{ chain -> Verdict.Return(mask(chain.proceed())) }", "::decide"):
            src = f"""
fun a() {{
    xposed.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(FailOpenHooker(log, n, {decision}))
}}
fun decide(chain: Chain): Verdict = Verdict.Return(mask(chain.proceed()))
"""
            self.assertTrue(any("calls chain.proceed" in f for f in findings(src)), decision)

    def test_transform_decision_passes(self):
        src = """
fun a() { hooks.sink(m, n, ::decide) }
fun decide(chain: Chain): Verdict = Verdict.Transform { mask(it) }
"""
        self.assertEqual([], findings(src))


class MiscTest(unittest.TestCase):
    def test_eager_class_init_is_flagged(self):
        for call in ('Class.forName(name, true, loader)', 'Class.forName(appClass)', 'Class.forName(f(x), true, l)'):
            self.assertTrue(findings(f"fun a() {{ {call} }}"), call)

    def test_platform_class_lookups_pass(self):
        for call in ('Class.forName("android.app.ActivityThread")', "Class.forName(IO_BRIDGE)",
                     "Class.forName(name, false, loader)"):
            self.assertEqual([], findings(f"fun a() {{ {call} }}"), call)

    def test_handler_thread_needs_a_handler_in_its_function(self):
        bad = "fun a() { HandlerThread(n).start() }\nfun b() { t.uncaughtExceptionHandler = h }"
        good = "fun a() { val t = HandlerThread(n); t.uncaughtExceptionHandler = h; t.start() }"
        self.assertTrue(findings(bad))
        self.assertEqual([], findings(good))

    def test_not_null_assertion_is_flagged(self):
        self.assertTrue(findings("fun a() = Int::class.javaPrimitiveType!!"))

    def test_comments_and_strings_never_count(self):
        src = 'fun a() { log("xposed.hook(m).intercept(h)") } // Class.forName(x, true, y) !!'
        self.assertEqual([], findings(src))


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
