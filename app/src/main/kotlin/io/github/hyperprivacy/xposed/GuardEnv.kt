package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.status.SinkNames
import io.github.libxposed.api.XposedInterface

/** Per-process collaborators shared by every guard installed in one hooked app process. */
internal class GuardEnv(
    val xposed: XposedInterface,
    val log: HookLog,
    val packageName: String,
    val classLoader: ClassLoader,
    val switches: RuleSwitches,
    val reporter: StatusReporter,
)

/** Names of enforcement sinks and the allowlist of them ([SinkNames.ALL]); defined in core so tests can check it. */
internal typealias Sinks = SinkNames
