package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.scope.PackageRules
import io.github.hyperprivacy.xposed.firewall.FirewallGuard
import io.github.hyperprivacy.xposed.system.SystemServerGuards
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

    /** Set only in system_server, by [onSystemServerStarting]. */
    @Volatile
    private var systemGuards: SystemServerGuards? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log.guard("module load") {
            // Not in other apps' processes: their logcat is read by their own SDKs (see HookLog).
            val ownPackage = param.processName.substringBefore(':')
            if (param.isSystemServer || ownPackage in Catalog.scope) {
                log.info("loaded in ${param.processName} (API $apiVersion, $frameworkName $frameworkVersion)")
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log.guard("firewall install") { FirewallGuard(this, log).install(param.classLoader) }
        log.guard("system guards install") {
            SystemServerGuards(this, log).also { systemGuards = it }.install(param.classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // In system_server, packages loaded into it (SettingsProvider) arrive as secondary packages.
        systemGuards?.let { guards ->
            log.guard("system package ${param.packageName}") {
                guards.onPackageReady(param.packageName, param.classLoader)
            }
        }
        // Only the process's own package: secondary packages (shared uid, createPackageContext)
        // share the process and would otherwise install duplicate hooks.
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        // Indexed lookup; catalog targets never read the tracker config.
        val rules = log.guard("rules for $pkg") { PackageRules.catalog.inProcess(pkg) } ?: return
        if (rules.isNotEmpty()) {
            log.guard("guards for $pkg") { AppGuards(this, log, pkg, param.classLoader, rules).install() }
            return
        }
        // Any other app is in scope because the user turned on tracker protection for it (or added
        // it by hand in LSPosed); without a stored scan result this returns after one preferences read.
        if (systemGuards != null) return
        log.guard("tracker guards for $pkg") { TrackerGuards.install(this, log, pkg, param.classLoader) }
    }
}
