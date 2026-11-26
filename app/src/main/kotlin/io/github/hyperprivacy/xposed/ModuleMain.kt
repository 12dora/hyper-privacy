package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.xposed.firewall.FirewallGuard
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Module entry, listed in `META-INF/xposed/java_init.list`.
 *
 * Dispatches by package to the guards for its rules. Every installer runs under [HookLog.guard],
 * so a failure only logs and never propagates into the host process.
 */
class ModuleMain : XposedModule() {
    private val log by lazy { HookLog(this) }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log.info("loaded in ${param.processName} (API $apiVersion, $frameworkName $frameworkVersion)")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log.guard("firewall install") { FirewallGuard(this, log).install(param.classLoader) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Only the process's own package: secondary packages (shared uid, createPackageContext)
        // share the process and would otherwise install duplicate hooks.
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        val rules = Catalog.rulesFor(pkg).filter { it.kind != RuleKind.FIREWALL }
        if (rules.isEmpty()) return
        log.guard("guards for $pkg") { AppGuards(this, log, pkg, param.classLoader, rules).install() }
    }
}
