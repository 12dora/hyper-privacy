package io.github.hyperprivacy.core.rule

/**
 * Which kind of privacy reporting a rule blocks. The catalog only contains reporting channels:
 * anything a vendor service needs to work (store, push, sync, security scan …) is out of scope.
 */
public enum class Category {
    /** Event and behaviour tracking. */
    ANALYTICS,

    /** Log, crash and performance upload. */
    DIAGNOSTICS,

    /** Ad attribution and ad reporting. */
    ADS,

    /** Device identifier collection. */
    DEVICE_ID,
}

/** The enforcement mechanism behind a rule. */
public enum class RuleKind {
    /** Whole-app network cut via the kernel firewall, enforced from system_server. */
    FIREWALL,

    /** Host-suffix block at the DNS and TLS sinks inside the target process. */
    DOMAIN,

    /** No-op a named public SDK method inside the target process. */
    METHOD,
}

/** Kind-specific data of a rule. The rule's [RuleKind] is derived from it, so they cannot disagree. */
public sealed interface RulePayload {
    public val kind: RuleKind

    /** Cut all traffic of every target package. Targets must own a non-shared app uid. */
    public data object Firewall : RulePayload {
        override val kind: RuleKind get() = RuleKind.FIREWALL
    }

    /** Block hosts equal to or under any of [suffixes]. */
    public data class Domains(val suffixes: List<String>) : RulePayload {
        override val kind: RuleKind get() = RuleKind.DOMAIN

        init {
            require(suffixes.isNotEmpty()) { "Domains payload needs at least one suffix" }
        }
    }

    /**
     * Replace methods of [className] matching [method] with a return-type default.
     *
     * Methods returning a reference type are skipped unless [allowNullReturn] is set, because
     * callers frequently dereference such results without a null check.
     */
    public data class Method(val className: String, val method: MethodPattern, val allowNullReturn: Boolean = false) :
        RulePayload {
        override val kind: RuleKind get() = RuleKind.METHOD

        init {
            require(className.isNotBlank()) { "className must not be blank" }
        }
    }
}

/**
 * A single user-toggleable protection.
 *
 * @property id stable identifier; persisted in preferences, so never rename a shipped id.
 * @property packages target package names the rule applies to.
 * @property evidence where the reporting channel was observed, e.g.
 *   `traffic: com.miui.analytics → tracking.miui.com` or `dex: Lcom/xiaomi/onetrack/OneTrack`.
 */
public data class Rule(
    val id: String,
    val packages: Set<String>,
    val title: String,
    val summary: String,
    val payload: RulePayload,
    val category: Category,
    val evidence: String,
    val defaultOn: Boolean = true,
) {
    val kind: RuleKind get() = payload.kind

    init {
        require(ID_PATTERN.matches(id)) { "Invalid rule id: $id" }
        require(packages.isNotEmpty()) { "Rule $id has no target package" }
        require(packages.all { PACKAGE_PATTERN.matches(it) }) { "Rule $id has an invalid package name" }
        require(evidence.isNotBlank()) { "Rule $id has no evidence" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
